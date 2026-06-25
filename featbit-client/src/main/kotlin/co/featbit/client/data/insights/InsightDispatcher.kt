package co.featbit.client.data.insights

import co.featbit.client.FBLogger
import co.featbit.client.model.Insight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

/**
 * Non-blocking, bounded, batching pipeline for analytics insight events.
 *
 * Each evaluation used to do `scope.launch { trackInsight.run(insight) }` — unbounded
 * fan-out that grew memory without limit under a slow network or a high evaluation rate.
 *
 * This dispatcher gives the caller a single non-suspending [offer] (drops the oldest
 * queued event on overflow rather than blocking the evaluation thread) and a single
 * background consumer that coalesces events into batches of up to [batchSize] or
 * [flushIntervalMs], whichever comes first.
 *
 * Lifecycle:
 * * [close] — fire-and-forget; the consumer will drain whatever it can before its parent
 *   scope is cancelled. May lose buffered events if the parent scope is cancelled
 *   immediately after.
 * * [closeAndDrain] — orderly suspend-shutdown used by `FBClientImpl.close`: waits (bounded)
 *   for the consumer to flush queued events and for [tracker]`.close()` to complete.
 */
internal class InsightDispatcher(
    private val tracker: TrackInsight,
    scope: CoroutineScope,
    private val logger: FBLogger,
    capacity: Int = DEFAULT_CAPACITY,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val closeFlushTimeoutMs: Long = DEFAULT_CLOSE_FLUSH_TIMEOUT_MS,
) : Closeable {

    private val channel: Channel<Insight> = Channel(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = null,
    )

    private val consumerJob: Job = scope.launch { consumeLoop() }

    /**
     * Enqueue an insight for asynchronous delivery. Never suspends. If the queue is full,
     * the oldest queued event is dropped — see [BufferOverflow.DROP_OLDEST]. With this
     * overflow policy [Channel.trySend] never reports failure for an open channel; calls
     * placed after [close] are silently no-ops.
     */
    fun offer(insight: Insight) {
        channel.trySend(insight)
    }

    private suspend fun consumeLoop() {
        val pending = ArrayList<Insight>(batchSize)
        // Two exit conditions:
        //  * `closeAndDrain` closes the channel → next `receiveCatching` returns closed → break.
        //  * The parent scope is cancelled (fire-and-forget `close` path) → `receiveCatching`
        //    throws `CancellationException` and the coroutine unwinds — also acceptable.
        while (true) {
            val first = channel.receiveCatching().getOrNull() ?: break
            pending += first

            drainAvailable(pending)
            if (pending.size < batchSize) {
                withTimeoutOrNull(flushIntervalMs) {
                    while (pending.size < batchSize) {
                        val next = channel.receiveCatching().getOrNull() ?: return@withTimeoutOrNull
                        pending += next
                    }
                }
            }

            // Defensive copy: the consumer reuses `pending` across iterations, but a tracker
            // may legitimately hold the batch reference (logging, retry, async-pipelining)
            // beyond its `run` call. Hand it an immutable snapshot.
            flush(pending.toList())
            pending.clear()
        }
        // Channel closed: best-effort flush of any items received after the last batch.
        drainAvailable(pending)
        if (pending.isNotEmpty()) flush(pending.toList())
    }

    private fun drainAvailable(into: MutableList<Insight>) {
        while (into.size < batchSize) {
            val next = channel.tryReceive().getOrNull() ?: break
            into += next
        }
    }

    private suspend fun flush(batch: List<Insight>) {
        try {
            tracker.run(batch)
        } catch (ex: Exception) {
            logger.error("Failed to flush insight batch of size ${batch.size}.", ex)
        }
    }

    override fun close() {
        // Fire-and-forget: parent scope decides how long the consumer has to drain.
        channel.close()
    }

    /**
     * Orderly shutdown: close the input channel and *suspend* until the consumer has flushed
     * its queue (bounded by [closeFlushTimeoutMs]), then close the underlying [tracker].
     * Used by `FBClientImpl.close` to give buffered insight events a chance to land.
     */
    suspend fun closeAndDrain() {
        channel.close()
        withTimeoutOrNull(closeFlushTimeoutMs) { consumerJob.join() }
        tracker.close()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 256
        const val DEFAULT_BATCH_SIZE: Int = 50
        const val DEFAULT_FLUSH_INTERVAL_MS: Long = 1_000L
        const val DEFAULT_CLOSE_FLUSH_TIMEOUT_MS: Long = 2_000L
    }
}
