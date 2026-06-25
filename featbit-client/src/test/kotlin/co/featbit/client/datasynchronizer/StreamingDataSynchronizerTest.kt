package co.featbit.client.datasynchronizer

import co.featbit.client.model.FBUser
import co.featbit.client.options.FBOptions
import co.featbit.client.store.DefaultMemoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Unit-level coverage for [StreamingDataSynchronizer] driven against [MockWebServer]'s
 * WebSocket support. Complements the env-gated E2E test (which exercises a real FeatBit
 * stack) by pinning the synchronizer's contract independently of the backend:
 *
 *  * `start()` opens a WebSocket, sends `data-sync`, and resolves once the server's reply
 *    has been upserted into the store.
 *  * `closeAndJoin()` preserves the store — flags fetched before close survive.
 *  * `pause()` closes the live WebSocket with NORMAL_CLOSURE + `"paused"` reason;
 *    `resume()` opens a fresh one with an advanced `timestamp` from the prior snapshot.
 *
 * Deterministic via `MockWebServer` + a short-`pingInterval` test client. Each test owns its
 * own MockWebServer instance to avoid cross-test bleed via OkHttp's connection pool.
 *
 * Note on the `if (closed) return` guard in `Listener.onMessage`:
 * A unit test that proves the guard's effect would need to invoke `onMessage` AFTER
 * `closed = true` was set but BEFORE the WebSocket finished tearing down. From the
 * server-side `WebSocket.send(...)` API, OkHttp drops the frame at the wire (because the
 * server-side socket is also closed after `closeAndJoin`) — so the listener's `onMessage`
 * is never invoked and the guard is never reached through the public surface. Verified by
 * mutating the guard to a no-op and watching this test class still pass. The guard is
 * exercised by the streaming E2E test (`FeatBitStreamingE2ETest`) which runs identify-time
 * swaps against a real FeatBit stack and would observe stale upserts if it regressed.
 */
class StreamingDataSynchronizerTest {

    private lateinit var server: MockWebServer

    /**
     * Per-test [WebSocketListener] capturing client-side traffic + giving tests a hook to
     * push server-side `data-sync` frames at deterministic moments.
     */
    private class ServerSide : WebSocketListener() {
        private val openedDeferred = CompletableDeferred<WebSocket>()
        private val firstMessageDeferred = CompletableDeferred<String>()
        private val closedDeferred = CompletableDeferred<CloseInfo>()
        val received = CopyOnWriteArrayList<String>()

        data class CloseInfo(val code: Int, val reason: String)

        suspend fun awaitOpen(): WebSocket =
            withTimeoutOrNull(5.seconds) { openedDeferred.await() }
                ?: error("server-side WebSocket never opened within 5s")

        suspend fun awaitFirstMessage(): String =
            withTimeoutOrNull(5.seconds) { firstMessageDeferred.await() }
                ?: error("server-side never received the data-sync within 5s")

        suspend fun awaitClose(): CloseInfo =
            withTimeoutOrNull(5.seconds) { closedDeferred.await() }
                ?: error("server-side never observed onClosing within 5s")

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            openedDeferred.complete(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            firstMessageDeferred.complete(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closedDeferred.complete(CloseInfo(code, reason))
            webSocket.close(1000, null)
        }
    }

    /**
     * Wire-format helper: build the server reply the SDK expects for a single-flag snapshot.
     * Matches the shape decoded by `StreamingDataSynchronizer.DataSyncPayload`. Inline
     * whitespace is tolerated by kotlinx-serialization's JSON parser (no normalization needed).
     */
    private fun dataSyncFrame(flagId: String, variation: String): String =
        """
        {"messageType":"data-sync","data":{"eventType":"full","userKeyId":"u1","featureFlags":[
            {"id":"$flagId","variation":"$variation","variationType":"boolean",
             "variationId":"v1","sendToExperiment":false,"matchReason":"fallthrough"}
        ]}}
        """.trimIndent()

    private fun streamingOptions(): FBOptions {
        // MockWebServer hands out `http://...` URLs; FBEndpoints accepts that form and rewrites
        // to `/streaming` — verified by `FBEndpointsTest`. Using http:// directly keeps the
        // test free of the `ws://` rewrite branch (already covered by `FBEndpointsTest`).
        val baseUrl = server.url("/").toString()
        return FBOptions.Builder("test-secret")
            .streaming(baseUrl)
            .event(baseUrl)
            .build()
    }

    /**
     * Snappy OkHttpClient — production default pings every 20s which is far slower than tests
     * want to run. A 1s ping isn't required for these tests but keeps idle sockets quick.
     */
    private fun snappyClient(): OkHttpClient = OkHttpClient.Builder()
        .pingInterval(1, TimeUnit.SECONDS)
        .build()

    /**
     * Parse the client's outgoing data-sync envelope so tests can assert against typed JSON
     * rather than substring-matching the raw string.
     */
    private fun decodeClientEnvelope(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Contract: `start()` must (a) open the WebSocket, (b) send a `data-sync` envelope with
     * exact shape `{messageType: "data-sync", data: {user: {keyId, ...}, timestamp: 0}}`, and
     * (c) suspend until the server's reply has been processed into the store and `initialized`
     * flips true.
     *
     * Mutation that would fail this:
     *   * Removing `payload.featureFlags.forEach(store::upsert)` from `handleMessage`.
     *   * Skipping `initializedFlag.compareAndSet(false, true)` on first message.
     *   * `sendDataSync` emitting wrong messageType, wrong user.keyId, or non-zero initial
     *     timestamp (the load-bearing "this is the first connection" invariant).
     */
    @Test
    fun `start opens websocket, sends data-sync, and initializes store from server snapshot`() = runBlocking {
        val serverSide = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))

        val store = DefaultMemoryStore()
        val sync = StreamingDataSynchronizer(
            options = streamingOptions(),
            user = FBUser.builder("u1").name("bob").build(),
            store = store,
            httpClient = snappyClient(),
        )

        try {
            val readyDeferred = async { sync.start() }

            val ws = serverSide.awaitOpen()
            val clientMessage = serverSide.awaitFirstMessage()

            // Structural assertions — would catch a regression that sent the wrong messageType,
            // omitted the user key, or used a non-zero initial timestamp.
            val envelope = decodeClientEnvelope(clientMessage)
            assertEquals("data-sync", envelope["messageType"]?.jsonPrimitive?.content)
            val data = envelope["data"]?.jsonObject ?: error("missing data: $clientMessage")
            assertEquals("u1", data["user"]?.jsonObject?.get("keyId")?.jsonPrimitive?.content)
            assertEquals(
                "initial timestamp must be 0 — proves this is the first connection",
                0L,
                data["timestamp"]?.jsonPrimitive?.long,
            )

            ws.send(dataSyncFrame("flag1", "true"))

            val ready = readyDeferred.await()

            assertTrue("start() reported initialized=true", ready)
            assertTrue("synchronizer reports initialized", sync.initialized)
            assertEquals(
                "flag1 landed in the store from the server snapshot",
                "true",
                store.get("flag1")?.variation,
            )
        } finally {
            sync.closeAndJoin()
        }
    }

    /**
     * Contract: `closeAndJoin` must NOT clear the store, and must NOT roll back `initialized`.
     * After close, evaluators continue serving the last-known data (the SDK's
     * offline-after-close mode).
     *
     * Mutation that would fail this:
     *   * Adding `store.clear()` or similar to `close()` / `closeAndJoin()`.
     *   * `closeAndJoin` mistakenly upserting empty payloads.
     *   * `closeAndJoin` flipping `initializedFlag` back to false.
     */
    @Test
    fun `closeAndJoin preserves store state and initialized flag`() = runBlocking {
        val serverSide = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))

        val store = DefaultMemoryStore()
        val sync = StreamingDataSynchronizer(
            options = streamingOptions(),
            user = FBUser.builder("u1").build(),
            store = store,
            httpClient = snappyClient(),
        )

        val readyDeferred = async { sync.start() }
        val ws = serverSide.awaitOpen()
        serverSide.awaitFirstMessage()
        ws.send(dataSyncFrame("flag-keep", "alpha"))
        assertTrue(readyDeferred.await())
        assertEquals("alpha", store.get("flag-keep")?.variation)
        assertTrue(sync.initialized)

        sync.closeAndJoin()

        assertEquals(
            "store contents survive closeAndJoin — close is for streaming lifecycle, not data",
            "alpha",
            store.get("flag-keep")?.variation,
        )
        assertTrue("initialized flag is sticky across close", sync.initialized)
    }

    /**
     * Contract: `pause()` closes the live WebSocket with NORMAL_CLOSURE (1000) and reason
     * `"paused"`. `resume()` opens a fresh WebSocket and re-sends `data-sync` with the
     * *advanced* timestamp (set by `handleMessage` after the first snapshot was processed),
     * so the server knows where to pick up from.
     *
     * Mutation that would fail this:
     *   * `pause()` not closing the WebSocket.
     *   * `pause()` closing with a non-1000 code or wrong reason.
     *   * `resume()` not calling `connect()` (no second server-side onOpen).
     *   * `resume()` re-using `timestamp=0` instead of the value advanced by `handleMessage`.
     */
    @Test
    fun `pause closes websocket with reason and resume reconnects with advanced timestamp`() = runBlocking {
        val firstSide = ServerSide()
        val secondSide = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(firstSide))
        server.enqueue(MockResponse().withWebSocketUpgrade(secondSide))

        val store = DefaultMemoryStore()
        val sync = StreamingDataSynchronizer(
            options = streamingOptions(),
            user = FBUser.builder("u1").build(),
            store = store,
            httpClient = snappyClient(),
        )

        try {
            val readyDeferred = async { sync.start() }
            val firstWs = firstSide.awaitOpen()
            firstSide.awaitFirstMessage()
            firstWs.send(dataSyncFrame("flag1", "true"))
            assertTrue("first connection initialized", readyDeferred.await())

            // Pause closes the WebSocket. Server side observes onClosing with the exact code
            // + reason from `webSocket?.close(NORMAL_CLOSURE, "paused")` (StreamingDataSync:168).
            sync.pause()
            val close = firstSide.awaitClose()
            assertEquals("pause uses NORMAL_CLOSURE", 1000, close.code)
            assertEquals("pause reason pinned for observability", "paused", close.reason)

            // Resume opens the second pre-enqueued upgrade. The fresh data-sync MUST advance
            // its `timestamp` — anything else would re-fetch the snapshot we just stored.
            sync.resume()
            val secondWs = secondSide.awaitOpen()
            val resumeMessage = secondSide.awaitFirstMessage()
            assertNotNull("resume opened the second WebSocket", secondWs)

            val resumeEnvelope = decodeClientEnvelope(resumeMessage)
            assertEquals("data-sync", resumeEnvelope["messageType"]?.jsonPrimitive?.content)
            val resumeData = resumeEnvelope["data"]?.jsonObject ?: error("missing data: $resumeMessage")
            val resumeTimestamp = resumeData["timestamp"]?.jsonPrimitive?.long
                ?: error("missing timestamp: $resumeMessage")
            assertTrue(
                "resume must send an advanced timestamp (was=$resumeTimestamp)",
                resumeTimestamp > 0L,
            )
        } finally {
            sync.closeAndJoin()
        }
    }
}
