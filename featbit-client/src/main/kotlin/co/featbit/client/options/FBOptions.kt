package co.featbit.client.options

import co.featbit.client.DefaultLogger
import co.featbit.client.FBLogger
import co.featbit.client.model.FeatureFlag
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Configuration for an [co.featbit.client.FBClient]. Build instances with [Builder].
 */
public class FBOptions internal constructor(
    /** Whether the client is offline. When true, no network calls to FeatBit are made. */
    public val offline: Boolean,
    /** Feature flags used as initial data before the first synchronization with FeatBit. */
    public val bootstrap: List<FeatureFlag>,
    /** The SDK secret for your FeatBit environment. */
    public val secret: String,
    /** The data synchronization mode. Defaults to [DataSyncMode.Polling]. */
    public val dataSyncMode: DataSyncMode,
    /** The base URL of the polling (evaluation) service, e.g. `https://app-eval.featbit.co`. */
    public val pollingUri: String,
    /** The interval between polls. */
    public val pollingInterval: Duration,
    /** The base WebSocket URL of the streaming (evaluation) service, e.g. `wss://app-eval.featbit.co`. */
    public val streamingUri: String,
    /** The base URL of the event (insight) service, e.g. `https://app-eval.featbit.co`. */
    public val eventUri: String,
    /** The logger used by the SDK. */
    public val logger: FBLogger,
) {
    /**
     * Fluent builder for [FBOptions].
     *
     * @param secret your FeatBit environment secret. May be empty when [offline] is used
     *               together with [bootstrap].
     */
    public class Builder(private val secret: String = "") {
        private var offline: Boolean = false
        private var bootstrap: List<FeatureFlag> = emptyList()
        private var dataSyncMode: DataSyncMode = DataSyncMode.Polling
        private var pollingUri: String = DEFAULT_URI
        private var pollingInterval: Duration = DEFAULT_POLLING_INTERVAL
        private var streamingUri: String = DEFAULT_STREAMING_URI
        private var eventUri: String = DEFAULT_URI
        private var logger: FBLogger = DefaultLogger()

        /** Configures polling synchronization against [pollingUri] at an optional [interval]. */
        public fun polling(pollingUri: String, interval: Duration? = null): Builder = apply {
            dataSyncMode = DataSyncMode.Polling
            this.pollingUri = pollingUri
            if (interval != null) pollingInterval = interval
        }

        /**
         * Configures real-time streaming synchronization against [streamingUri]
         * (a `ws://` or `wss://` URL, e.g. `wss://app-eval.featbit.co`).
         */
        public fun streaming(streamingUri: String): Builder = apply {
            dataSyncMode = DataSyncMode.Streaming
            this.streamingUri = streamingUri
        }

        /** Sets the base URL of the event (insight) service. */
        public fun event(eventUri: String): Builder = apply { this.eventUri = eventUri }

        /** Puts the client into offline mode. */
        public fun offline(offline: Boolean): Builder = apply { this.offline = offline }

        /** Provides feature flags to bootstrap the SDK before the first synchronization. */
        public fun bootstrap(bootstrap: List<FeatureFlag>): Builder = apply {
            this.bootstrap = bootstrap
        }

        /** Sets the logger used by the SDK. Defaults to a [DefaultLogger]. */
        public fun logger(logger: FBLogger): Builder = apply { this.logger = logger }

        public fun build(): FBOptions = FBOptions(
            offline = offline,
            bootstrap = bootstrap,
            secret = secret,
            dataSyncMode = dataSyncMode,
            pollingUri = pollingUri,
            pollingInterval = pollingInterval,
            streamingUri = streamingUri,
            eventUri = eventUri,
            logger = logger,
        )

        public companion object {
            private const val DEFAULT_URI: String = "http://localhost:5100"
            private const val DEFAULT_STREAMING_URI: String = "ws://localhost:5100"
            private val DEFAULT_POLLING_INTERVAL: Duration = 5.minutes
        }
    }
}
