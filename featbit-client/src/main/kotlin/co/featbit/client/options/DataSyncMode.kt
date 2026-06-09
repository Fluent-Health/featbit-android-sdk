package co.featbit.client.options

/**
 * The data synchronization mode used by the SDK.
 *
 * [Polling] periodically fetches flags over HTTP. [Streaming] keeps a WebSocket open to the
 * evaluation server and receives flag changes in real time (as the JS/RN SDKs do).
 */
public enum class DataSyncMode {
    Polling,
    Streaming,
}
