package co.featbit.client.datasynchronizer

import co.featbit.client.model.FBUser
import co.featbit.client.options.FBOptions
import co.featbit.client.store.DefaultMemoryStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class PollingDataSynchronizerTest {

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

    private fun options() = FBOptions.Builder("env-secret")
        // large interval: only the first immediate poll runs during the test
        .polling(server.url("/").toString(), interval = 10.minutes)
        .event(server.url("/").toString())
        .build()

    @Test
    fun `first successful poll initializes and populates store`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"featureFlags":[{"id":"flag1","variation":"true"}]}}""",
            ),
        )

        val store = DefaultMemoryStore()
        val user = FBUser.builder("u1").build()
        val sync = PollingDataSynchronizer(options(), user, store)

        val ready = sync.start()
        sync.close()

        assertTrue(ready)
        assertTrue(sync.initialized)
        assertEquals("true", store.get("flag1")?.variation)
    }

    @Test
    fun `fatal 401 stops polling and reports not ready`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val store = DefaultMemoryStore()
        val user = FBUser.builder("u1").build()
        val sync = PollingDataSynchronizer(options(), user, store)

        val ready = sync.start()
        sync.close()

        assertFalse(ready)
        assertFalse(sync.initialized)
    }
}
