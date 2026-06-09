package co.featbit.client.options

/**
 * The data synchronization mode used by the SDK.
 *
 * Only [Polling] is currently supported, matching the .NET client SDK. Streaming
 * (WebSocket) synchronization is a planned future addition.
 */
public enum class DataSyncMode {
    Polling,
}
