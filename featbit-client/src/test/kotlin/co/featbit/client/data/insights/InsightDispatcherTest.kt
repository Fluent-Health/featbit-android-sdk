package co.featbit.client.data.insights

import co.featbit.client.FBLogger
import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import co.featbit.client.model.Insight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-case coverage for [InsightDispatcher]. The dispatcher sits on the evaluation hot path,
 * so we exercise: batch-by-size, batch-by-time, overflow-drops-oldest, tracker-exception
 * doesn't kill the consumer, [InsightDispatcher.closeAndDrain] actually flushes, and offer
 * after close is a silent no-op.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsightDispatcherTest {

    private fun insight(i: Int): Insight = Insight.forEvaluation(
        user = FBUser.builder("u$i").build(),
        flag = FeatureFlag(id = "f$i", variation = "v$i"),
        timestamp = i.toLong(),
    )

    private fun Insight.firstFlagKey(): String = variations[0].featureFlagKey

    private class RecordingTracker(
        private val onBatchSuspend: (suspend (List<Insight>) -> Unit)? = null,
        private val throwOnce: Boolean = false,
    ) : TrackInsight {
        val batches = mutableListOf<List<Insight>>()
        val closed = CompletableDeferred<Unit>()
        private var hasThrown = false

        override suspend fun run(batch: List<Insight>) {
            if (throwOnce && !hasThrown) {
                hasThrown = true
                throw RuntimeException("first batch fails")
            }
            onBatchSuspend?.invoke(batch)
            batches += batch
        }

        override fun close() {
            closed.complete(Unit)
        }
    }

    @Test
    fun `batches by size before the flush interval elapses`() = runTest {
        val tracker = RecordingTracker()
        val dispatcher = InsightDispatcher(tracker, this, FBLogger.NoOp, batchSize = 3)

        repeat(3) { dispatcher.offer(insight(it)) }
        advanceUntilIdle()

        assertEquals(1, tracker.batches.size)
        assertEquals(listOf("f0", "f1", "f2"), tracker.batches[0].map { it.firstFlagKey() })

        dispatcher.closeAndDrain()
    }

    @Test
    fun `batches by time when fewer than batchSize events arrive`() = runTest {
        val tracker = RecordingTracker()
        val dispatcher = InsightDispatcher(
            tracker, this, FBLogger.NoOp,
            batchSize = 50,
            flushIntervalMs = 1_000L,
        )

        dispatcher.offer(insight(1))
        dispatcher.offer(insight(2))
        advanceTimeBy(1_001L)
        runCurrent()

        assertEquals("one time-based batch", 1, tracker.batches.size)
        assertEquals(2, tracker.batches[0].size)

        dispatcher.closeAndDrain()
    }

    @Test
    fun `overflow drops the oldest event when queue is full`() = runTest {
        // Hold the tracker on its first batch so the channel actually fills behind it.
        val release = CompletableDeferred<Unit>()
        val tracker = RecordingTracker(onBatchSuspend = { release.await() })
        // Tiny capacity makes overflow trivial to trigger without 256+ allocations.
        val dispatcher = InsightDispatcher(
            tracker, this, FBLogger.NoOp,
            capacity = 4,
            batchSize = 100,
            flushIntervalMs = 10_000L,
        )

        repeat(8) { dispatcher.offer(insight(it)) }
        runCurrent()

        // Release the consumer + push past the flush interval so it batches what's queued.
        release.complete(Unit)
        advanceTimeBy(10_001L)
        advanceUntilIdle()

        // With consumer hung on its first `release.await()` AT THE TRACKER LEVEL — wait, no.
        // The 8 offers complete BEFORE the consumer's scope.launch is scheduled (we explicitly
        // did `runCurrent()` only after the offer loop). So all 8 offers race the consumer's
        // start: channel fills to capacity=4 with f0..f3, then f4..f7 each evict the head via
        // DROP_OLDEST. Consumer's first receive sees [f4, f5, f6, f7]. Flush waits for the
        // 10_000ms flush interval (batchSize=100 not reached), then emits a single batch.
        //
        // Exact-equality assertion (not "size <= 5 + contains f7") so a broken implementation
        // that delivered only [f7], kept f0..f3 instead of f4..f7, or emitted in wrong order
        // would fail this test.
        assertEquals("exactly one batch — the 4 surviving newest events", 1, tracker.batches.size)
        assertEquals(listOf("f4", "f5", "f6", "f7"), tracker.batches[0].map { it.firstFlagKey() })

        dispatcher.closeAndDrain()
    }

    @Test
    fun `tracker exception is logged and does not kill the consumer`() = runTest {
        val tracker = RecordingTracker(throwOnce = true)
        val dispatcher = InsightDispatcher(tracker, this, FBLogger.NoOp, batchSize = 1)

        dispatcher.offer(insight(1)) // first run throws — should be swallowed
        advanceUntilIdle()
        dispatcher.offer(insight(2)) // second batch should still go through
        advanceUntilIdle()

        assertEquals("only the surviving batch was recorded", 1, tracker.batches.size)
        assertEquals("f2", tracker.batches[0][0].firstFlagKey())

        dispatcher.closeAndDrain()
    }

    @Test
    fun `closeAndDrain flushes pending events and closes the tracker`() = runTest {
        val tracker = RecordingTracker()
        val dispatcher = InsightDispatcher(
            tracker, this, FBLogger.NoOp,
            batchSize = 100,
            flushIntervalMs = 10_000L,
        )

        dispatcher.offer(insight(1))
        dispatcher.offer(insight(2))
        runCurrent()
        assertTrue("no batch yet — still waiting on the flush timer", tracker.batches.isEmpty())

        dispatcher.closeAndDrain()

        assertEquals("close triggered a flush", 1, tracker.batches.size)
        assertEquals(2, tracker.batches[0].size)
        assertTrue("tracker was closed", tracker.closed.isCompleted)
    }

    @Test
    fun `offer after close is a silent no-op`() = runTest {
        val tracker = RecordingTracker()
        val dispatcher = InsightDispatcher(tracker, this, FBLogger.NoOp, batchSize = 1)

        dispatcher.closeAndDrain()

        // Should not throw even though the channel is closed.
        dispatcher.offer(insight(99))
        advanceUntilIdle()

        assertTrue("no batch sent after close", tracker.batches.isEmpty())
    }

    @Test
    fun `closeAndDrain bounded by closeFlushTimeoutMs when tracker hangs`() = runTest {
        // Tracker.run suspends forever — closeAndDrain must still return on its own timeout.
        // We host the dispatcher on `backgroundScope` so the leaked consumer coroutine is
        // cancelled when `runTest` finishes; otherwise the suspended `tracker.run` would
        // pin the test scope open.
        val trapped = Channel<Unit>(1)
        val tracker = object : TrackInsight {
            override suspend fun run(batch: List<Insight>) {
                trapped.receive() // never completes
            }
            override fun close() {}
        }
        val dispatcher = InsightDispatcher(
            tracker, backgroundScope, FBLogger.NoOp,
            batchSize = 1,
            closeFlushTimeoutMs = 250L,
        )

        dispatcher.offer(insight(1))
        runCurrent()

        // If the bounded timeout didn't work, this would hang the test forever.
        dispatcher.closeAndDrain()
    }
}
