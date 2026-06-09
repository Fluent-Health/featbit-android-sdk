package co.featbit.client.internal

import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import co.featbit.client.model.Insight
import co.featbit.client.options.FBOptions
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
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

    @Test
    fun `posts a single-element insight array to track endpoint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val options = FBOptions.Builder("env-secret")
            .polling(server.url("/").toString())
            .event(server.url("/").toString())
            .build()
        val user = FBUser.builder("u1").name("bob").build()
        val flag = FeatureFlag(id = "flag1", variation = "true", variationId = "v1")

        val tracker = HttpTrackInsight(options)
        tracker.run(Insight.forEvaluation(user, flag, timestamp = 123))
        tracker.close()

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/api/public/insight/track"))
        val body = recorded.body.readUtf8()
        assertTrue("payload should be a JSON array: $body", body.startsWith("["))
        assertTrue(body, body.contains("\"featureFlagKey\":\"flag1\""))
        assertTrue(body, body.contains("\"keyId\":\"u1\""))
    }
}
