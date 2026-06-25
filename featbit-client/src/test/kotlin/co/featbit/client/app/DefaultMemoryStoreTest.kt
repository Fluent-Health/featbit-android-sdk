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
}
