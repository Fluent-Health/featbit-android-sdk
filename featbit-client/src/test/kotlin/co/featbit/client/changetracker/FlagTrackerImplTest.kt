package co.featbit.client.changetracker

import app.cash.turbine.test
import co.featbit.client.model.FeatureFlag
import co.featbit.client.store.DefaultMemoryStore
import co.featbit.client.store.FlagValueChangedEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FlagTrackerImplTest {

    private fun flag(id: String, variation: String) = FeatureFlag(id = id, variation = variation)

    @Test
    fun `global subscriber receives all changes`() {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)
        val received = CopyOnWriteArrayList<FlagValueChangedEvent>()
        tracker.subscribe { received.add(it) }

        store.upsert(flag("a", "1"))
        store.upsert(flag("b", "2"))

        assertEquals(2, received.size)
    }

    @Test
    fun `keyed subscriber only receives its own key`() {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)
        val received = CopyOnWriteArrayList<FlagValueChangedEvent>()
        tracker.subscribe("a") { received.add(it) }

        store.upsert(flag("a", "1"))
        store.upsert(flag("b", "2"))

        assertEquals(1, received.size)
        assertEquals("a", received[0].key)
    }

    @Test
    fun `unsubscribe stops delivery`() {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)
        val received = CopyOnWriteArrayList<FlagValueChangedEvent>()
        val listener = co.featbit.client.store.FlagChangeListener { received.add(it) }
        tracker.subscribe(listener)
        tracker.unsubscribe(listener)

        store.upsert(flag("a", "1"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `flagChanges flow emits change events`() = runTest {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)

        tracker.flagChanges.test {
            store.upsert(flag("a", "1"))
            assertEquals(FlagValueChangedEvent("a", null, "1"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `close removes listener from store`() {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)
        val received = CopyOnWriteArrayList<FlagValueChangedEvent>()
        tracker.subscribe { received.add(it) }

        tracker.close()
        store.upsert(flag("a", "1"))

        assertTrue(received.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // capacity + fairness + exception tests (wider-scope audit H1)
    // ---------------------------------------------------------------------------------------

    /**
     * Contract: the SharedFlow uses `extraBufferCapacity = 64` (FlagTrackerImpl.kt:25). This
     * buffer is for ACTIVE-BUT-SLOW collectors so the emitter doesn't suspend while the
     * collector catches up. With a hung collector and replay=0, the emitter's `tryEmit` fills
     * the buffer first, then DROPS subsequent events. Pins the audit's H1 finding — "a burst
     * of upserts during a fresh data-sync snapshot can overflow silently."
     *
     * Setup: register a HUNG collector (suspends in the lambda so it can't drain), then push
     * more events than capacity. The first 64 land in the buffer, the rest are dropped at
     * `tryEmit`. Then release the collector and assert the bounded receive count.
     *
     * Honest scope: this test pins capacity + overflow-drops behavior. It does NOT change the
     * production contract (no overflow signal to caller) — that's a follow-up design decision
     * flagged in the audit. Future refactors that lift capacity or add an overflow callback
     * will need to update this assertion.
     *
     * Mutation that would fail this:
     *   * Lowering `extraBufferCapacity` (e.g. to 16) — drop earlier, far fewer than 64 land.
     *   * Switching to `MutableSharedFlow(replay = N)` with a smaller buffer — same.
     *   * Changing `tryEmit` to suspending `emit` — emitter would suspend instead of dropping;
     *     test would observe more than 64 events after release (since none were dropped).
     */
    @Test
    fun `SharedFlow buffer caps at extraBufferCapacity under hung-collector burst`() {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)

        val subscribed = CountDownLatch(1)   // collector has issued its first awaitItem
        val firstReceived = CountDownLatch(1) // collector has drained the very first event
        val releaseGate = CountDownLatch(1)  // test releases the collector to drain the burst
        val received = CopyOnWriteArrayList<FlagValueChangedEvent>()

        // Hung collector on its own thread: signals when subscribed + when first event arrives,
        // then blocks on releaseGate so subsequent emits must fit into extraBufferCapacity or
        // be dropped.
        val collectorThread = Thread {
            kotlinx.coroutines.runBlocking {
                tracker.flagChanges.collect { event ->
                    if (received.isEmpty()) {
                        // First event delivered.
                        received.add(event)
                        firstReceived.countDown()
                        releaseGate.await()
                    } else {
                        received.add(event)
                    }
                }
            }
        }
        collectorThread.isDaemon = true
        collectorThread.start()

        // Wait for the thread to actually subscribe before pushing any events. We approximate
        // "subscribed" by pushing a single warmup event in a tight loop until it lands.
        val subscribeDeadline = System.currentTimeMillis() + 5_000
        var warmupAttempts = 0
        while (firstReceived.count > 0 && System.currentTimeMillis() < subscribeDeadline) {
            store.upsert(flag("warmup", "v$warmupAttempts"))
            warmupAttempts++
            Thread.sleep(10)
        }
        subscribed.countDown()
        assertEquals(
            "warmup event reached the collector within 5s (got $warmupAttempts attempts)",
            0L,
            firstReceived.count,
        )
        val baseline = received.size // = 1

        // Push the burst. The collector is hung on `releaseGate`. Each emit either fills
        // extraBufferCapacity (64) or drops at tryEmit. Suspending emit would NOT drop.
        val burst = 200
        repeat(burst) { i -> store.upsert(flag("k$i", "v$i")) }

        // Release the collector and let it drain whatever the buffer held.
        releaseGate.countDown()
        Thread.sleep(500) // drain window

        val afterBurst = received.size - baseline
        assertTrue(
            "buffered emissions ≤ 64 (got $afterBurst) — extraBufferCapacity is the ceiling",
            afterBurst <= 64,
        )
        assertTrue(
            "at least 1 buffered event survived (got $afterBurst) — buffer is not size 0",
            afterBurst >= 1,
        )

        collectorThread.interrupt()
    }

    /**
     * Contract: a slow callback-style subscriber does not block other subscribers receiving
     * the same event. `subscribers.forEach { it.onChange(event) }` is sequential per-event,
     * so this pins what "slow" means: the SECOND subscriber (after the slow one) is delayed
     * BUT still fires.
     *
     * Honest scope: this is not "concurrent fan-out" — production iterates synchronously. The
     * test pins that the iteration completes even when one subscriber takes time.
     *
     * Mutation that would fail this:
     *   * `subscribers.forEach { ... }` rewritten to `subscribers.first().onChange(event)` —
     *     only the slow one fires, fast one never does.
     *   * Wrapping each `onChange` in a `launch` without awaiting — fast subscriber might fire
     *     before the slow one, but both eventually fire. Current sequential semantic is the
     *     contract.
     */
    @Test
    fun `slow subscriber does not prevent subsequent subscribers from firing`() {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)

        val slowEntered = CountDownLatch(1)
        val slowFinished = CountDownLatch(1)
        val fastFired = AtomicBoolean(false)
        val slowFired = AtomicBoolean(false)

        // Order matters: slow subscribes first → slow runs first per forEach iteration.
        tracker.subscribe { _ ->
            slowEntered.countDown()
            // Block briefly. Bounded so a regression doesn't hang the suite.
            Thread.sleep(200)
            slowFired.set(true)
            slowFinished.countDown()
        }
        tracker.subscribe { _ -> fastFired.set(true) }

        // Trigger fan-out on the test thread (production also runs subscribers synchronously
        // from the upsert thread, see DefaultMemoryStore.upsert line 44).
        store.upsert(flag("a", "1"))

        // CRITICAL: by the time upsert returns, BOTH must have ALREADY fired — no wait.
        // forEach is synchronous on the caller thread, so a regression that wraps each
        // onChange in `launch()` (making fan-out asynchronous) would fail this immediate
        // check because the fast subscriber wouldn't yet have run when upsert returned.
        assertTrue(
            "slow subscriber fired synchronously on caller thread",
            slowFired.get(),
        )
        assertTrue(
            "fast subscriber ALSO fired synchronously — NO wait, no latch.await — pins " +
                "the synchronous-fan-out invariant; a launch()-based regression would fail here",
            fastFired.get(),
        )
        // Latches are sanity backstops, not the load-bearing assertions.
        assertEquals(0L, slowEntered.count)
        assertEquals(0L, slowFinished.count)
    }

    /**
     * Contract: subscriber exceptions MUST NOT starve other subscribers. Each callback is
     * wrapped in `safeNotify` (FlagTrackerImpl.kt:39-49) so a throwing subscriber:
     *   1. Does not propagate its exception out of `dispatch` (and thus out of `store.upsert`).
     *   2. Does not halt iteration over the subscriber list.
     *
     * This is a data-loss-prevention guarantee — callback-style APIs cannot let one consumer
     * silently break delivery to all consumers registered later.
     *
     * Mutation that would fail this:
     *   * Removing the `try { ... } catch (t: Throwable) { ... }` wrapper in `safeNotify` —
     *     the exception escapes, `forEach` halts, after-thrower never fires AND the throw
     *     propagates out of `store.upsert`.
     *   * Catching the exception but rethrowing — same effect.
     */
    @Test
    fun `subscriber exception does not starve other subscribers`() {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)

        val beforeFired = AtomicBoolean(false)
        val afterFired = AtomicBoolean(false)

        tracker.subscribe { _ -> beforeFired.set(true) }
        tracker.subscribe { _ -> throw RuntimeException("subscriber-thrown") }
        tracker.subscribe { _ -> afterFired.set(true) }

        // The exception MUST be swallowed inside dispatch — store.upsert must return normally.
        store.upsert(flag("a", "1"))

        assertTrue("before-thrower subscriber fired", beforeFired.get())
        assertTrue(
            "after-thrower subscriber ALSO fired — exception was swallowed by safeNotify",
            afterFired.get(),
        )
    }

    /**
     * Contract: keyed subscriptions to different keys are isolated. A subscriber on key "a"
     * never receives events for key "b" — even when both keys change in quick succession.
     *
     * Mutation that would fail this:
     *   * `keyedSubscribers[event.key]?.forEach` replaced with iterating ALL keyed lists —
     *     subscribers for the wrong key would also fire.
     *   * Bucketing keyed subscribers by something other than key (e.g. global list).
     */
    @Test
    fun `keyed subscribers on different keys are isolated`() {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)
        val aEvents = CopyOnWriteArrayList<FlagValueChangedEvent>()
        val bEvents = CopyOnWriteArrayList<FlagValueChangedEvent>()

        tracker.subscribe("a") { aEvents.add(it) }
        tracker.subscribe("b") { bEvents.add(it) }

        store.upsert(flag("a", "1"))
        store.upsert(flag("b", "2"))
        store.upsert(flag("a", "3"))

        assertEquals(
            "key-a subscriber sees only its key (2 events)",
            listOf("1", "3"),
            aEvents.map { it.newValue },
        )
        assertEquals(
            "key-b subscriber sees only its key (1 event)",
            listOf("2"),
            bEvents.map { it.newValue },
        )
    }

    /**
     * Contract: the flagChanges Flow has `replay = 0`. Events emitted while there are NO
     * collectors are dropped at `tryEmit` and not replayed to a late-arriving collector.
     * A collector that subscribes AFTER an upsert sees only subsequent upserts.
     *
     * Note on `extraBufferCapacity = 64`: that buffer is for slow ACTIVE collectors (so a
     * burst of emissions doesn't suspend the emitter while the collector catches up). It is
     * NOT a pre-subscription replay buffer — `replay` controls that, and we set it to 0
     * implicitly.
     *
     * Mutation that would fail this:
     *   * Adding `replay = N` (any N ≥ 1) to the MutableSharedFlow constructor — late
     *     collectors would replay the "before" event.
     */
    @Test
    fun `flagChanges Flow has no replay — late subscribers do not see prior events`() = runTest {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)

        // Emit 5 events BEFORE anyone collects. With replay=0 and no active collector,
        // tryEmit drops them all. Five (not one) so a regression that adds `replay = 1` or
        // `replay = 3` would still be caught — the test must see "after" first, not any
        // pre-subscription value.
        repeat(5) { i -> store.upsert(flag("a", "before-$i")) }

        tracker.flagChanges.test {
            store.upsert(flag("a", "after"))
            val first = awaitItem()
            assertEquals(
                "late collector sees only the post-subscription event — none of the 5 " +
                    "pre-subscription values were replayed",
                "after",
                first.newValue,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
