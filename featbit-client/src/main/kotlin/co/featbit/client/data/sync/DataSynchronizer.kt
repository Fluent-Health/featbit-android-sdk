package co.featbit.client.data.sync

import java.io.Closeable

/**
 * Synchronizes feature flag data from FeatBit into the SDK's store.
 */
internal interface DataSynchronizer : Closeable {
    /** Whether the synchronizer has completed its first successful synchronization. */
    val initialized: Boolean

    /**
     * Starts synchronization and suspends until the first synchronization completes.
     *
     * @return `true` once initialized; `false` if a fatal error stopped synchronization.
     */
    suspend fun start(): Boolean

    /**
     * Temporarily stops network activity (e.g. app backgrounded or offline) while preserving
     * already-synced data. Safe to call repeatedly. Default: no-op.
     */
    fun pause() {}

    /**
     * Resumes synchronization after a [pause] and forces an immediate resync. Safe to call
     * repeatedly. Default: no-op.
     */
    fun resume() {}

    /**
     * Orderly shutdown: signal stop, then suspend until every in-flight upsert / network call
     * has completed. Use this from [FBClientImpl.identify] so a late polling response from the
     * previous user cannot land in the store *after* the user-swap.
     *
     * The non-suspending [close] is the fire-and-forget fallback for `Closeable` semantics;
     * implementations should make it forward to [closeAndJoin] on a best-effort basis.
     */
    suspend fun closeAndJoin() {
        close()
    }
}
