package co.featbit.client

/**
 * Lifecycle + health events emitted by [FBClient.events]. Applications wire a collector to
 * surface transport / decode failures to their own observability stack (Sentry breadcrumb,
 * Timber log, etc.) — the SDK itself never captures third-party observability.
 *
 * Events are emitted on a hot channel: subscribe before calling [FBClient.start] to avoid
 * missing the first [Ready]. Late subscribers observe subsequent events only.
 */
public sealed interface FBEvent {

    /** Fired the first time the sync layer completes an initial payload for the current user. */
    public data object Ready : FBEvent

    /**
     * Fired when the streaming websocket is (re)connecting after a drop or the initial handshake.
     * Not fired for the very first connection attempt.
     */
    public data object Reconnecting : FBEvent

    /**
     * Fired when a streaming payload fails to decode or a single flag entry within a batch is
     * malformed. [recoverable] is `true` when the sync loop continues after the failure — either
     * because a single flag was skipped or because a reconnect is expected shortly.
     *
     * @param cause the throwable that surfaced from the sync layer.
     * @param recoverable whether flag evaluations continue against the last-known snapshot.
     */
    public data class SyncError(val cause: Throwable, val recoverable: Boolean) : FBEvent

    /**
     * Fired for transport-level failures (websocket close with a non-normal code, network
     * unavailable, HTTP polling failure). The SDK will attempt to reconnect autonomously.
     */
    public data class TransportError(val cause: Throwable) : FBEvent
}
