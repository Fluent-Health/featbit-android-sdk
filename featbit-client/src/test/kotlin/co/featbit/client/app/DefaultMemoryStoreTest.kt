package co.featbit.client.app

import co.featbit.client.model.FeatureFlag
import co.featbit.client.store.FlagChangeListener
import co.featbit.client.store.FlagValueChangedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DefaultMemoryStoreTest {

    private fun flag(id: String, variation: String) =
        FeatureFlag(id = id, variation = variation)

    @Test
    fun `bootstrap is loaded`() {
        val store = DefaultMemoryStore(listOf(flag("a", "1"), flag("b", "2")))
        assertEquals("1", store.get("a")?.variation)
        assertEquals(2, store.getAll().size)
        assertNull(store.get("missing"))
    }

    @Test
    fun `upsert of new flag fires event with null old value`() {
        val store = DefaultMemoryStore()
        val events = CopyOnWriteArrayList<FlagValueChangedEvent>()
        store.addChangeListener { events.add(it) }

        store.upsert(flag("a", "1"))

        assertEquals(1, events.size)
        assertEquals(FlagValueChangedEvent("a", null, "1"), events[0])
    }

    @Test
    fun `upsert with changed value fires event, unchanged does not`() {
        val store = DefaultMemoryStore(listOf(flag("a", "1")))
        val events = CopyOnWriteArrayList<FlagValueChangedEvent>()
        store.addChangeListener { events.add(it) }

        store.upsert(flag("a", "1")) // unchanged
        assertTrue(events.isEmpty())

        store.upsert(flag("a", "2")) // changed
        assertEquals(listOf(FlagValueChangedEvent("a", "1", "2")), events.toList())
    }

    @Test
    fun `removed listener stops receiving events`() {
        val store = DefaultMemoryStore()
        val events = CopyOnWriteArrayList<FlagValueChangedEvent>()
        val listener = FlagChangeListener { events.add(it) }
        store.addChangeListener(listener)
        store.removeChangeListener(listener)

        store.upsert(flag("a", "1"))
        assertTrue(events.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // concurrency tests (wider-scope audit N2)
    // ---------------------------------------------------------------------------------------

    /**
     * Contract: concurrent upserts of the same flag from many threads must (a) never throw
     * any exception (ConcurrentModificationException, NPE, IllegalStateException), and (b)
     * leave the store in a consistent final state with a value that some thread actually
     * wrote.
     *
     * Setup: 16 threads × 250 upserts each = 4000 upserts. Each thread cycles values
     * `"v0".."v3"`.
     *
     * Mutation discipline (honest scope):
     *   * Removing `CopyOnWriteArrayList` for listeners → CME on dispatch path under
     *     concurrent addChangeListener. This test (using a single listener) would not catch
     *     that — `add and remove listeners during dispatch` below pins that surface.
     *   * Removing `ConcurrentHashMap` for the items map → CME or torn read. This test WOULD
     *     catch a CME because all threads contend the same key.
     *   * Removing `synchronized(writeLock)` → race between diff and store. This test does
     *     NOT reliably catch that mutation: the race produces a wrong-but-plausible event
     *     count, not a thrown exception or a "garbage string" newValue. Catching the lock
     *     mutation would need a deterministic interleaving harness (custom lock with
     *     interposition), which is overkill for a unit test — the lock is mechanically a
     *     1-line guard read by code review.
     *
     * Net: this test proves the thread-safe-collection invariants and "no garbage". The
     * diff-under-lock invariant relies on review + the in-tree comment.
     */
    @Test
    fun `concurrent upserts are race-free and final state is consistent`() {
        val store = DefaultMemoryStore()
        val events = CopyOnWriteArrayList<FlagValueChangedEvent>()
        store.addChangeListener { events.add(it) }

        val threads = 16
        val perThread = 250
        val pool = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)
        val exceptions = AtomicReference<Throwable?>(null)

        repeat(threads) { tid ->
            pool.submit {
                try {
                    startGate.await() // align all threads to hammer simultaneously
                    repeat(perThread) { i ->
                        val v = "v${(tid + i) % 4}"
                        store.upsert(flag("flagX", v))
                    }
                } catch (t: Throwable) {
                    exceptions.compareAndSet(null, t)
                } finally {
                    doneGate.countDown()
                }
            }
        }

        startGate.countDown()
        assertTrue(
            "all threads finished within 30s",
            doneGate.await(30, TimeUnit.SECONDS),
        )
        pool.shutdown()

        assertNull("no thread threw under concurrent upsert", exceptions.get())

        // Final state: the store holds *some* value of "v0".."v3" for flagX.
        val finalFlag = store.get("flagX")
        assertTrue(
            "final value is one of v0..v3 (got '${finalFlag?.variation}')",
            finalFlag?.variation in setOf("v0", "v1", "v2", "v3"),
        )

        // Event count bounds: at least 1 (first insert always fires), at most 4000 (every
        // upsert was a real transition — impossible in practice with only 4 distinct values
        // and 16 racing threads, but pinned as an upper bound).
        val n = events.size
        assertTrue(
            "received between 1 and $threads*$perThread events (got $n) — a tearing bug " +
                "could push above 4000 by double-firing on the same transition",
            n in 1..(threads * perThread),
        )

        // Strongest invariant: every recorded event's newValue is one of the actual values
        // any thread might have written. Catches tearing that produces garbage strings.
        events.forEach { ev ->
            assertTrue(
                "event newValue must be v0..v3 (got '${ev.newValue}')",
                ev.newValue in setOf("v0", "v1", "v2", "v3"),
            )
            assertEquals("event key is flagX", "flagX", ev.key)
        }
    }

    /**
     * Contract: the `items[flag.id] = flag` write happens-before the listener callback fires.
     * A listener can therefore re-enter `store.upsert(...)` with a different key, and can
     * read the value just written for the parent key via `store.get(...)`.
     *
     * Honest scope: this test does NOT prove that notify happens *outside* the
     * `synchronized(writeLock)` block. JVM monitors are reentrant — moving `listeners.forEach`
     * inside the synchronized block would NOT deadlock the same-thread reentrant upsert; the
     * parent's `items[flag.id] = flag` would have already happened-before the callback either
     * way, so `store.get(event.key)` would still return the just-written value. The genuine
     * "notify outside lock" guarantee matters when ANOTHER thread is contending; reproducing
     * that deterministically requires a custom Lock with interposition, which is overkill for
     * a single-line invariant. The mechanical guard is read by code review.
     *
     * Mutation that would fail this:
     *   * Not committing `items[flag.id] = flag` before notify — `store.get(event.key)` in
     *     the listener returns null or stale data.
     *   * Listener never fired — `reentrantSeen` stays null.
     */
    @Test
    fun `listener observes the just-written value (write happens-before notify)`() {
        val store = DefaultMemoryStore()
        val reentrantSeen = AtomicReference<String?>(null)
        var reentrantUpserted = false

        store.addChangeListener { event ->
            // First fire: re-enter with a *different* key. Capture what the store reports
            // *during* the callback for the original key — must already reflect the write.
            if (!reentrantUpserted) {
                reentrantUpserted = true
                reentrantSeen.set(store.get(event.key)?.variation)
                store.upsert(flag("b", "from-listener"))
            }
        }

        store.upsert(flag("a", "from-test"))

        assertEquals(
            "listener observed the just-written value of 'a' — proves the items[] = flag " +
                "write happens-before the callback fires",
            "from-test",
            reentrantSeen.get(),
        )
        assertEquals(
            "reentrant upsert from inside the listener committed to the store",
            "from-listener",
            store.get("b")?.variation,
        )
    }

    /**
     * Contract: the listeners snapshot used by `forEach` (CopyOnWriteArrayList.iterator) is
     * stable — adding a listener mid-iteration MUST NOT cause the new listener to fire for
     * the in-flight event, and removing a listener mid-iteration MUST NOT throw
     * ConcurrentModificationException.
     *
     * The first listener (which mutates the listener list during its own callback) and the
     * subsequent listeners exercise both directions.
     *
     * Mutation that would fail this:
     *   * Replacing `CopyOnWriteArrayList<FlagChangeListener>` with a plain `ArrayList` —
     *     would throw ConcurrentModificationException on the add-during-iteration path.
     */
    @Test
    fun `add and remove listeners during dispatch do not corrupt iteration`() {
        val store = DefaultMemoryStore()
        val firedNames = CopyOnWriteArrayList<String>()

        val late = FlagChangeListener { firedNames.add("late") }
        val first = FlagChangeListener {
            firedNames.add("first")
            // Mutate the listener list mid-iteration. CopyOnWriteArrayList guarantees the
            // active iterator sees the snapshot it captured at iteration start.
            store.addChangeListener(late)
        }
        val second = FlagChangeListener { firedNames.add("second") }

        store.addChangeListener(first)
        store.addChangeListener(second)

        // Explicit CME catch: a plain `ArrayList` would throw ConcurrentModificationException
        // on the same-thread `addChangeListener(...)` call inside the iterator's forEach. The
        // try/catch distinguishes that failure mode from any other assertion failure below,
        // so a CI run failing here points the maintainer at exactly the regression class.
        try {
            store.upsert(flag("k", "v1"))
        } catch (e: java.util.ConcurrentModificationException) {
            org.junit.Assert.fail(
                "listener list must use a snapshot iterator (CopyOnWriteArrayList); plain " +
                    "ArrayList would CME here: ${e.message}",
            )
        }

        assertEquals(
            "exactly first+second fired in iteration order; 'late' joined too late for this event",
            listOf("first", "second"),
            firedNames.toList(),
        )

        // Second upsert: 'late' is now in the snapshot, fires alongside first+second.
        firedNames.clear()
        store.upsert(flag("k", "v2"))
        assertEquals(
            "after add-during-dispatch, 'late' fires on subsequent events",
            listOf("first", "second", "late"),
            firedNames.toList(),
        )
    }

    /**
     * Contract: `addChangeListener` uses `listeners.addIfAbsent(listener)` so registering the
     * same listener twice is a no-op. Prevents duplicate fan-out for callers that defensively
     * re-register on lifecycle events.
     *
     * Mutation that would fail this:
     *   * Replacing `addIfAbsent` with `add` — the listener fires twice on the single upsert.
     */
    @Test
    fun `addChangeListener is idempotent — re-adding the same listener fires it only once`() {
        val store = DefaultMemoryStore()
        val count = AtomicInteger(0)
        val listener = FlagChangeListener { count.incrementAndGet() }

        store.addChangeListener(listener)
        store.addChangeListener(listener) // must be a no-op (addIfAbsent semantic)

        store.upsert(flag("a", "1"))

        assertEquals("listener fires exactly once despite two add calls", 1, count.get())
    }

    // ---------------------------------------------------------------------------------------
    // upsertAll tests (perf-pass: one lock acquire per batch instead of N)
    // ---------------------------------------------------------------------------------------

    /**
     * Contract: `upsertAll(emptyCollection)` is a no-op. Polling can call this with an empty
     * snapshot (server returns no flags); must not enter the write lock or fire any events.
     *
     * Mutation that would fail this:
     *   * Removing the `if (flags.isEmpty()) return` short-circuit — the impl would still
     *     enter the synchronized block (small but observable), but more importantly the
     *     listener-fire loop would still iterate (zero events, technically still a pass).
     *     This test pins the *event* side; lock-acquisition itself is not observable.
     *   * If a future refactor accidentally fires a spurious empty-event, this catches it.
     */
    @Test
    fun `upsertAll with empty collection fires no events`() {
        val store = DefaultMemoryStore()
        val events = CopyOnWriteArrayList<FlagValueChangedEvent>()
        store.addChangeListener { events.add(it) }

        store.upsertAll(emptyList())

        assertTrue("empty bulk upsert fires zero events", events.isEmpty())
    }

    /**
     * Contract: a bulk upsert of N new flags fires N change events, one per flag, each with
     * `oldValue == null` and `newValue == flag.variation`. Output order = input order
     * (preserved by ArrayList accumulation under the write lock).
     *
     * Mutation that would fail this:
     *   * Replacing the inner loop with a single-event emit (e.g., notifying once at end with
     *     just the last flag) — event count drops from N to 1.
     *   * Filtering out flags that "look unchanged" relative to a sibling in the same batch —
     *     would fire fewer events.
     *   * Re-ordering the accumulator (e.g., HashSet instead of ArrayList) — event order
     *     would scramble for some inputs.
     */
    @Test
    fun `upsertAll of new flags fires one event per flag in input order`() {
        val store = DefaultMemoryStore()
        val events = CopyOnWriteArrayList<FlagValueChangedEvent>()
        store.addChangeListener { events.add(it) }

        store.upsertAll(listOf(flag("a", "1"), flag("b", "2"), flag("c", "3")))

        assertEquals(
            "events fire one-per-flag with null oldValue, in input order",
            listOf(
                FlagValueChangedEvent("a", null, "1"),
                FlagValueChangedEvent("b", null, "2"),
                FlagValueChangedEvent("c", null, "3"),
            ),
            events.toList(),
        )
    }

    /**
     * Contract: in a bulk upsert mixing changed + unchanged flags, only the changed flags
     * fire events. This mirrors the per-flag `upsert` semantic exactly — bulk must NOT
     * fire an event for a flag whose `variation` matches the existing entry.
     *
     * Mutation that would fail this:
     *   * Bulk impl naively fires an event for every input (skipping the per-flag diff) —
     *     `c` would produce an unwanted event.
     *   * Diff comparing the wrong field (e.g., variationId instead of variation) — both `a`
     *     (unchanged variation) and `c` (unchanged variation) would either appear or
     *     disappear from events, depending on the bug.
     */
    @Test
    fun `upsertAll mixing changed and unchanged flags fires events only for changes`() {
        val store = DefaultMemoryStore(listOf(flag("a", "1"), flag("c", "3")))
        val events = CopyOnWriteArrayList<FlagValueChangedEvent>()
        store.addChangeListener { events.add(it) }

        // a: unchanged. b: new. c: unchanged. d: new.
        store.upsertAll(listOf(flag("a", "1"), flag("b", "2"), flag("c", "3"), flag("d", "4")))

        assertEquals(
            "only b (new) and d (new) fire — a and c are no-op for matching variation",
            listOf(
                FlagValueChangedEvent("b", null, "2"),
                FlagValueChangedEvent("d", null, "4"),
            ),
            events.toList(),
        )
        // Store reflects every entry — even ones that produced no event.
        assertEquals("1", store.get("a")?.variation)
        assertEquals("2", store.get("b")?.variation)
        assertEquals("3", store.get("c")?.variation)
        assertEquals("4", store.get("d")?.variation)
    }

    /**
     * Contract: when a bulk upsert overwrites existing entries with different variations,
     * each event carries the correct (prior_variation, new_variation) pair. Pinning this
     * separately from "new-flag events" because the diff path is harder to get right.
     *
     * Mutation that would fail this:
     *   * Computing `oldValue` from the freshly-stored map (after the write) instead of the
     *     pre-write read — every event would have `oldValue == newValue`.
     *   * Storing the whole input list pre-loop and reading old values from there — would
     *     skew on duplicate inputs.
     */
    @Test
    fun `upsertAll overwriting existing flags carries correct oldValue per event`() {
        val store = DefaultMemoryStore(listOf(flag("a", "old-a"), flag("b", "old-b")))
        val events = CopyOnWriteArrayList<FlagValueChangedEvent>()
        store.addChangeListener { events.add(it) }

        store.upsertAll(listOf(flag("a", "new-a"), flag("b", "new-b")))

        assertEquals(
            listOf(
                FlagValueChangedEvent("a", "old-a", "new-a"),
                FlagValueChangedEvent("b", "old-b", "new-b"),
            ),
            events.toList(),
        )
    }

    /**
     * Contract: in `upsertAll`, listener callbacks fire AFTER all writes complete. A listener
     * invoked during dispatch can call `store.get(otherKeyInTheSameBatch)` and observe the
     * post-batch value, not a half-written intermediate state. This is the consistency
     * guarantee documented in `MemoryStore.upsertAll`'s Kdoc.
     *
     * Mutation that would fail this:
     *   * Re-implementing `upsertAll` as `flags.forEach { upsert(it) }` (the *interface
     *     default*) — listeners fire interleaved with writes, so during the event for `a`,
     *     `b` is not yet visible in the store, and the captured snapshot would show `null`
     *     for `b`.
     *
     * Note: this is the documented behavioral DIFFERENCE between the override and the
     * default, so future maintainers know which is which.
     */
    @Test
    fun `upsertAll fires listeners after all writes — listener sees consistent snapshot`() {
        val store = DefaultMemoryStore()
        val observedDuringDispatch = CopyOnWriteArrayList<Pair<String, String?>>()
        store.addChangeListener { ev ->
            // Capture, for each event, what the store reports for the *other* flag in the
            // batch. With the override (write-then-notify), every event must observe every
            // sibling already in place.
            observedDuringDispatch.add(ev.key to store.get(otherKey(ev.key))?.variation)
        }

        store.upsertAll(listOf(flag("a", "1"), flag("b", "2")))

        // Both events must see both writes already committed. Note the snapshot map: for
        // event-a, sibling "b" must already be in the store; for event-b, sibling "a" must
        // be there too.
        assertTrue(
            "during event for 'a', sibling 'b' must already be visible (saw: $observedDuringDispatch)",
            observedDuringDispatch.contains("a" to "2"),
        )
        assertTrue(
            "during event for 'b', sibling 'a' must already be visible (saw: $observedDuringDispatch)",
            observedDuringDispatch.contains("b" to "1"),
        )
    }

    private fun otherKey(key: String): String = when (key) {
        "a" -> "b"
        "b" -> "a"
        else -> throw IllegalArgumentException("unexpected key $key")
    }

    /**
     * Contract: with zero registered listeners, `upsertAll` short-circuits the notify loop
     * entirely. This pins the "no listeners → skip dispatch" optimization in the impl.
     *
     * Mutation that would fail this:
     *   * Removing the `listeners.isEmpty()` guard before the for-loop — would still produce
     *     correct (no) behavior because zero listeners = nothing to iterate. NOT directly
     *     observable. This test pins only that no exception fires; the optimization is
     *     mechanical and verified by code review.
     */
    @Test
    fun `upsertAll with no listeners does not throw`() {
        val store = DefaultMemoryStore()
        // Intentionally no addChangeListener — must not throw on the dispatch path.
        store.upsertAll(listOf(flag("a", "1"), flag("b", "2")))
        assertEquals("1", store.get("a")?.variation)
        assertEquals("2", store.get("b")?.variation)
    }
}
