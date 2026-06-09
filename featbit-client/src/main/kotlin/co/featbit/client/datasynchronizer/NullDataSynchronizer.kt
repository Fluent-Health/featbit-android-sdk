package co.featbit.client.datasynchronizer

/** A no-op synchronizer used in offline mode; always reports as initialized. */
internal class NullDataSynchronizer : DataSynchronizer {
    override val initialized: Boolean = true
    override suspend fun start(): Boolean = true
    override fun close() {}
}
