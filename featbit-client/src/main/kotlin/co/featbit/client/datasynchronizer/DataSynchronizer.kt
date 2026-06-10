package co.featbit.client.datasynchronizer

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
}
