package co.featbit.client.internal

import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import co.featbit.client.model.Insight
import co.featbit.client.options.FBOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackInsightTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newTracker(): HttpTrackInsight {
        val options = FBOptions.Builder("env-secret")
            .polling(server.url("/").toString())
            .event(server.url("/").toString())
            .build()
        return HttpTrackInsight(options)
    }

    private fun insight(flagId: String, userKey: String = "u1", ts: Long = 100): Insight {
        val user = FBUser.builder(userKey).name(userKey).build()
        val flag = FeatureFlag(id = flagId, variation = "true", variationId = "v$flagId")
        return Insight.forEvaluation(user, flag, ts)
    }

    @Test
    fun `posts a single-element insight array to track endpoint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val tracker = newTracker()
        tracker.run(insight("flag1"))
        tracker.close()

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/api/public/insight/track"))
        val body = recorded.body.readUtf8()
        assertTrue("payload should be a JSON array: $body", body.startsWith("["))
        assertTrue(body, body.contains("\"featureFlagKey\":\"flag1\""))
        assertTrue(body, body.contains("\"keyId\":\"u1\""))
    }

    // ---------------------------------------------------------------------------------------
    // batch + error-handling + lifecycle tests (wider-scope audit M8)
    // ---------------------------------------------------------------------------------------

    /**
     * Contract: `run(batch)` serializes the entire batch as a JSON array containing one
     * object per insight. Each element has its own `keyId` (from the per-insight user) and
     * featureFlagKey. The batch is sent in a single POST.
     *
     * Mutation that would fail this:
     *   * Iterating the batch with one POST per insight — server would record N requests.
     *   * Serializing only the first insight — JSON array would have 1 element.
     *   * Wrong serializer (`Insight.serializer()` instead of `ListSerializer(Insight.serializer())`)
     *     — payload would be a JSON object, not array.
     */
    @Test
    fun `multi-element batch is posted as a single JSON array`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val tracker = newTracker()
        val batch = listOf(
            insight("flag-a", userKey = "u1", ts = 100L),
            insight("flag-b", userKey = "u2", ts = 200L),
            insight("flag-c", userKey = "u3", ts = 300L),
        )
        tracker.run(batch)
        tracker.close()

        // Exactly one HTTP request — the whole batch in a single POST.
        assertEquals("batch posted as one request", 1, server.requestCount)

        val body = server.takeRequest().body.readUtf8()
        val arr = Json.parseToJsonElement(body).jsonArray
        assertEquals("array has one entry per insight", 3, arr.size)

        // Pin per-element shape: keyId from user + featureFlagKey + timestamp from variation.
        // Without per-field assertions, a serializer regression dropping timestamp or
        // mis-serializing variations would slip through.
        val keys = arr.map { it.jsonObject["user"]!!.jsonObject["keyId"]!!.jsonPrimitive.content }
        assertEquals(listOf("u1", "u2", "u3"), keys)

        val variations = arr.map { it.jsonObject["variations"]!!.jsonArray[0].jsonObject }
        val flagKeys = variations.map { it["featureFlagKey"]!!.jsonPrimitive.content }
        assertEquals(listOf("flag-a", "flag-b", "flag-c"), flagKeys)

        // Timestamp pins prove the wire-format field survives serialization in-order.
        val timestamps = variations.map { it["timestamp"]!!.jsonPrimitive.content.toLong() }
        assertEquals(listOf(100L, 200L, 300L), timestamps)

        // sendToExperiment is a bool field on VariationInsight; pin it to catch regressions
        // that silently drop optional fields (e.g. a wrong @SerialName).
        val sendToExperiment = variations.map { it["sendToExperiment"]!!.jsonPrimitive.content }
        assertEquals(listOf("false", "false", "false"), sendToExperiment)
    }

    /**
     * Contract: an empty batch is a no-op — production guards with `if (batch.isEmpty()) return`.
     * No HTTP request is issued.
     *
     * Mutation that would fail this:
     *   * Removing the empty-batch guard — `post(endpoint, payload)` would still fire with
     *     an empty JSON array `[]`, producing one stray request.
     */
    @Test
    fun `empty batch is a no-op — no HTTP request issued`() = runBlocking {
        // No MockResponse enqueued — any actual request would result in MockWebServer's
        // default behavior (HTTP 404 from no enqueued response, but we want zero requests).
        val tracker = newTracker()
        tracker.run(emptyList())
        tracker.close()

        assertEquals("no request issued for empty batch", 0, server.requestCount)
    }

    /**
     * Contract: network failures are swallowed — `run` catches the generic Exception and
     * logs. Production must NOT propagate the failure to the caller because insight loss is
     * acceptable but breaking the calling code path (evaluation, identify) is not.
     *
     * Setup: force a genuine network error by disconnecting the socket at request start.
     * OkHttp throws IOException; production's `catch (ex: Exception)` swallows it. (Note:
     * a 5xx response by itself does NOT throw — `execute()` returns a normal Response
     * object with status=500. Only socket/connect errors raise exceptions.)
     *
     * Mutation that would fail this:
     *   * Removing the `catch (ex: Exception)` block — the IOException would propagate.
     *   * Replacing `Exception` with a narrower type that doesn't include IOException —
     *     same outcome.
     */
    @Test
    fun `network error is swallowed and does not propagate`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val tracker = newTracker()
        // Must return normally — IOException from the disconnected socket is caught.
        tracker.run(insight("flag1"))
        tracker.close()

        // The connection attempt was made (server saw it before disconnect).
        assertTrue("network attempt was made", server.requestCount >= 1)
    }

    /**
     * Contract (HONEST observation):
     *
     * Production `run` has `catch (ce: CancellationException) { throw ce }` ahead of the
     * generic `catch (ex: Exception)`. The CE-specific catch only catches CE thrown at a
     * *suspension* point. But the body of `post(endpoint, payload)` is
     * `withContext(Dispatchers.IO) { client.newCall(request).execute() }` — `execute()` is a
     * *blocking* JVM call. When the parent coroutine cancels, kotlinx delivers it via thread
     * interrupt; OkHttp converts that to `InterruptedIOException`, which is a generic
     * Exception, not CancellationException. The CE-specific catch is unreachable for HTTP
     * post — the generic branch swallows + logs.
     *
     * This test pins OBSERVED behavior: cancelling the launched job that holds `tracker.run`
     * leaves `job.isCancelled == true` (kotlinx side) but the lambda body itself returns
     * normally because the underlying InterruptedIOException was swallowed.
     *
     * Mutation that would fail this:
     *   * Removing the entire try/catch around the post call — the InterruptedIOException
     *     would propagate, the launched lambda would terminate exceptionally, and
     *     `bodyReturnedNormally` would stay false.
     *
     * Follow-up: if structured-concurrency-aware InterruptedIOException handling is desired,
     * `post()` should map InterruptedIOException → CancellationException, letting the
     * existing `catch (ce)` branch rethrow it. Out of scope for this commit.
     */
    @Test
    fun `cancellation does not propagate as exception out of tracker run (current behavior)`() {
        // No enqueued response: server accepts the socket but never responds. Use a snappy
        // OkHttpClient (1s readTimeout) so the test's wall-clock budget isn't bound by
        // OkHttp's 8s default — cancellation must surface within ~1s either way.
        val snappy = okhttp3.OkHttpClient.Builder()
            .readTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val options = FBOptions.Builder("env-secret")
            .polling(server.url("/").toString())
            .event(server.url("/").toString())
            .build()
        val tracker = HttpTrackInsight(options, httpClient = snappy)

        var bodyReturnedNormally = false
        var bodyThrew = false
        try {
            runBlocking {
                val job = launch {
                    try {
                        tracker.run(insight("flag1"))
                        bodyReturnedNormally = true
                    } catch (ce: CancellationException) {
                        bodyThrew = true
                        throw ce
                    } catch (t: Throwable) {
                        bodyThrew = true
                        throw t
                    }
                }
                delay(100) // let HTTP call start
                job.cancelAndJoin()
            }
        } finally {
            tracker.close()
        }
        assertTrue(
            "current production: tracker.run swallows the InterruptedIOException via the " +
                "generic catch — body returns normally despite cancellation",
            bodyReturnedNormally,
        )
        assertEquals("body did NOT throw", false, bodyThrew)
    }

    /**
     * Contract: [NoopTrackInsight] is the offline implementation. Both `run` and `close`
     * are no-ops — must not throw, must not perform any I/O.
     *
     * Mutation that would fail this:
     *   * NoopTrackInsight.run throwing UnsupportedOperationException — typical defensive
     *     trap for accidentally-called methods. Production explicitly opts for no-op.
     */
    @Test
    fun `NoopTrackInsight run and close are no-ops`() = runBlocking {
        // Run with a batch + a single insight + empty batch + close. All must return normally.
        NoopTrackInsight.run(emptyList())
        NoopTrackInsight.run(listOf(insight("flag1")))
        NoopTrackInsight.run(insight("flag2"))
        NoopTrackInsight.close()

        // Sanity: no MockWebServer interaction because NoopTrackInsight has no client.
        assertEquals(0, server.requestCount)
    }

    /**
     * Contract: `HttpTrackInsight` shuts down its owned OkHttp dispatcher on `close()`
     * (mirrors `FbApiClient.ownsClient` pattern). Verified indirectly by ensuring close()
     * doesn't throw + subsequent run() doesn't make any new HTTP request because the
     * client's dispatcher is shutting down (or, depending on OkHttp behavior, the request
     * may still go through; the strict-binding contract is "close() doesn't throw").
     *
     * Mutation that would fail this:
     *   * close() throwing — would fail this test outright.
     *   * close() being a no-op AND a later run() succeeding without a server-side request
     *     count change (would indicate close() didn't release resources, but we can't pin
     *     dispatcher state from outside without reflection).
     */
    @Test
    fun `close completes without throwing`() {
        val tracker = newTracker()
        // Multiple closes must be safe (idempotent).
        tracker.close()
        tracker.close()
        // Sanity: no MockWebServer interaction from the close calls.
        assertEquals(0, server.requestCount)
    }
}
