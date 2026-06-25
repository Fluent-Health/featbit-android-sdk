package co.featbit.client.data.http

import co.featbit.client.model.FBUser
import co.featbit.client.options.FBOptions
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetUserFlagsTest {

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

    private fun newClient(): GetUserFlags {
        val options = FBOptions.Builder("env-secret")
            .polling(server.url("/").toString())
            .event(server.url("/").toString())
            .build()
        val user = FBUser.builder("u1").name("bob").custom("country", "FR").build()
        return GetUserFlags(options, user)
    }

    @Test
    fun `200 parses data featureFlags and sends auth, user-agent, timestamp`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"featureFlags":[
                    {"id":"flag1","variation":"true","variationType":"boolean",
                     "variationId":"v1","sendToExperiment":false,"matchReason":"rule match"}
                ]}}""".trimIndent(),
            ),
        )

        val client = newClient()
        val response = client.run(timestamp = 0)
        client.close()

        assertFalse(response.isError)
        assertEquals(1, response.flags.size)
        assertEquals("flag1", response.flags[0].id)
        assertEquals("true", response.flags[0].variation)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("0", recorded.requestUrl?.queryParameter("timestamp"))
        assertTrue(recorded.path!!.startsWith("/api/public/sdk/client/latest-all"))
        assertEquals("env-secret", recorded.getHeader("Authorization"))
        assertEquals("featbit-kotlin-client-sdk", recorded.getHeader("User-Agent"))
    }

    @Test
    fun `empty body yields empty flags`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val client = newClient()
        val response = client.run(0)
        client.close()
        assertFalse(response.isError)
        assertTrue(response.flags.isEmpty())
    }

    @Test
    fun `401 is fatal, 500 is transient`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val client = newClient()
        val fatal = client.run(0)
        assertTrue(fatal.isFatal)
        assertTrue(fatal.isError)

        server.enqueue(MockResponse().setResponseCode(500))
        val transient = client.run(0)
        client.close()
        assertFalse(transient.isFatal)
        assertTrue(transient.isError)
    }

    @Test
    fun `wire-shape mismatch on data field throws (not silent zero flags)`() = runBlocking {
        // Regression: an earlier refactor silently downgraded malformed payloads to "no flags",
        // which is indistinguishable from a legitimate empty configuration and made the SDK
        // serve defaults forever. The shape mismatch must surface as an exception so the
        // caller's `safePoll` logs it as an error.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data":"oops, not an object"}"""),
        )
        val client = newClient()
        assertThrows(Exception::class.java) {
            runBlocking { client.run(0) }
        }
        client.close()
    }

    @Test
    fun `missing data field yields empty flags (legitimate empty config)`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = newClient()
        val response = client.run(0)
        client.close()
        assertFalse(response.isError)
        assertTrue("no flags configured for user — not an error", response.flags.isEmpty())
    }

    @Test
    fun `missing featureFlags key yields empty flags`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{}}"""))
        val client = newClient()
        val response = client.run(0)
        client.close()
        assertFalse(response.isError)
        assertTrue(response.flags.isEmpty())
    }
}
