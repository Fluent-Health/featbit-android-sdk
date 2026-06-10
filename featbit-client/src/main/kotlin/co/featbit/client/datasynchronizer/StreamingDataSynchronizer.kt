package co.featbit.client.datasynchronizer

import co.featbit.client.internal.ConnectionToken
import co.featbit.client.model.EndUser
import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import co.featbit.client.options.FBOptions
import co.featbit.client.store.MemoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

/**
 * Synchronizes feature flags in real time over a WebSocket to FeatBit's `/streaming` endpoint.
 *
 * On connect it sends a `data-sync` message (with the current user and last-seen timestamp);
 * the server replies with a `full` snapshot and subsequently pushes `patch` updates whenever a
 * flag changes. Flags are upserted into the shared [store], which drives evaluation and the
 * flag tracker exactly as in polling mode. An application-level `ping` heartbeat plus
 * exponential-backoff reconnection keep the connection healthy.
 */
internal class StreamingDataSynchronizer(
    options: FBOptions,
    private val user: FBUser,
    private val store: MemoryStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) : DataSynchronizer {

    private val logger = options.logger
    private val secret = options.secret
    private val streamingEndpoint = options.streamingUri.toStreamingHttpUrl()

    private val startTask = CompletableDeferred<Boolean>()
    private val initializedFlag = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)

    @Volatile
    private var timestamp: Long = 0

    @Volatile
    private var closed = false

    @Volatile
    private var paused = false

    @Volatile
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempts = 0

    override val initialized: Boolean get() = initializedFlag.get()

    override suspend fun start(): Boolean {
        connect()
        return startTask.await()
    }

    private fun connect() {
        if (closed || paused) return
        val url = streamingEndpoint.newBuilder()
            .addQueryParameter("type", "client")
            .addQueryParameter("version", "2")
            .addQueryParameter("token", ConnectionToken.encode(secret))
            .build()
        logger.debug { "Opening streaming connection to $url" }
        webSocket = httpClient.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            sendDataSync(webSocket)
            startHeartbeat(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handleMessage(text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (code != NORMAL_CLOSURE) scheduleReconnect("closed: $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect(t.message ?: "connection failure")
        }
    }

    private fun sendDataSync(webSocket: WebSocket) {
        val message = StreamingJson.encodeToString(
            ClientMessage.serializer(DataSyncData.serializer()),
            ClientMessage("data-sync", DataSyncData(user.toEndUser(), timestamp)),
        )
        webSocket.send(message)
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                webSocket.send(PING_MESSAGE)
            }
        }
    }

    private fun handleMessage(text: String) {
        try {
            val envelope = StreamingJson.decodeFromString(ServerEnvelope.serializer(), text)
            if (envelope.messageType != "data-sync" || envelope.data == null) return

            val payload = StreamingJson.decodeFromJsonElement(DataSyncPayload.serializer(), envelope.data)
            payload.featureFlags.forEach(store::upsert)
            timestamp = System.currentTimeMillis()

            if (initializedFlag.compareAndSet(false, true)) {
                startTask.complete(true)
                logger.info("Streaming data synchronizer initialized for user ${user.key}.")
            }
        } catch (ex: Exception) {
            logger.error("Failed to handle streaming message.", ex)
        }
    }

    override fun pause() {
        if (closed || paused) return
        paused = true
        reconnecting.set(false)
        heartbeatJob?.cancel()
        webSocket?.close(NORMAL_CLOSURE, "paused")
        webSocket = null
        logger.debug { "Streaming paused." }
    }

    override fun resume() {
        if (closed || !paused) return
        paused = false
        reconnectAttempts = 0
        logger.debug { "Streaming resumed; reconnecting and resyncing." }
        connect()
    }

    private fun scheduleReconnect(reason: String) {
        if (closed || paused) return
        heartbeatJob?.cancel()
        if (!reconnecting.compareAndSet(false, true)) return

        val attempt = ++reconnectAttempts
        val backoff = min(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl min(attempt, 6)) + Random.nextLong(250)
        logger.warn("Streaming disconnected ($reason); reconnecting in ${backoff}ms (attempt $attempt).")
        scope.launch {
            delay(backoff)
            reconnecting.set(false)
            connect()
        }
    }

    override fun close() {
        closed = true
        heartbeatJob?.cancel()
        webSocket?.close(NORMAL_CLOSURE, null)
        webSocket = null
        scope.cancel()
        startTask.complete(false)
    }

    @Serializable
    private class ClientMessage<T>(val messageType: String, val data: T)

    @Serializable
    private class DataSyncData(val user: EndUser, val timestamp: Long)

    @Serializable
    private class ServerEnvelope(val messageType: String = "", val data: JsonElement? = null)

    @Serializable
    private class DataSyncPayload(
        val eventType: String = "",
        @SerialName("userKeyId") val userKeyId: String = "",
        val featureFlags: List<FeatureFlag> = emptyList(),
    )

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val HEARTBEAT_INTERVAL_MS = 20_000L
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val PING_MESSAGE = """{"messageType":"ping","data":{}}"""

        val StreamingJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Accepts `ws(s)://` (or `http(s)://`) and returns the `/streaming` HTTP(S) URL OkHttp uses. */
        fun String.toStreamingHttpUrl() =
            replaceFirst(Regex("^ws"), "http").toHttpUrl().newBuilder().addPathSegment("streaming").build()
    }
}
