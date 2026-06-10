package co.featbit.client.e2e

import co.featbit.client.FBClientImpl
import co.featbit.client.model.FBUser
import co.featbit.client.options.FBOptions
import co.featbit.client.store.FlagValueChangedEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Full-stack E2E for **streaming** sync: a real [FBClientImpl] in [co.featbit.client.options.DataSyncMode.Streaming]
 * connects over a WebSocket to a real FeatBit evaluation server (via [FeatBitStack]) and receives
 * a server-side flag change as a real-time push.
 *
 * Gated by `FEATBIT_E2E=1` (requires Docker), like [FeatBitE2ETest].
 */
class FeatBitStreamingE2ETest {

    @Before
    fun gate() {
        Assume.assumeTrue("Set FEATBIT_E2E=1 (and have Docker) to run E2E tests", ENABLED)
    }

    @Test
    fun `streams the seeded flag and receives a server-side change as a push`() = runBlocking {
        // evaluationBaseUrl is http://host:port; streaming uses the ws:// form of the same host.
        val streamingUrl = seed.evaluationBaseUrl.replaceFirst("http", "ws")

        val options = FBOptions.Builder(seed.clientSecret)
            .streaming(streamingUrl)
            .event(seed.evaluationBaseUrl)
            .backgroundGracePeriod(1.seconds) // short grace so the test runs fast
            .build()

        val client = FBClientImpl(options, FBUser.builder("e2e-stream-user").name("stream").build())
        val changes = CopyOnWriteArrayList<FlagValueChangedEvent>()

        try {
            assertTrue("streaming client should connect and initialize", client.start(15.seconds))
            assertTrue(client.initialized)

            val detail = client.boolVariationDetail(seed.flagKey, default = false)
            assertTrue("seeded flag should evaluate to true over streaming", detail.value)
            assertEquals("default", detail.reason)

            client.flagTracker.subscribe(seed.flagKey) { changes.add(it) }
            stack.toggleFlag(enabled = false)

            // Streaming should deliver the change as a push — well within this window.
            val flipped = awaitUntil(15.seconds) { !client.boolVariation(seed.flagKey, default = true) }
            assertTrue("streaming SDK should receive the server-side change", flipped)
            assertTrue("a flag-change event should have fired", changes.isNotEmpty())
            assertEquals("false", changes.last().newValue)

            // --- lifecycle pause/resume: background, change server-side, then foreground ---
            client.setForeground(false)
            delay(1_500) // exceed the 1s grace so the stream pauses
            stack.toggleFlag(enabled = true)

            // While paused, the SDK must NOT receive the change.
            val sawWhilePaused = awaitUntil(3.seconds) { client.boolVariation(seed.flagKey, default = false) }
            assertFalse("paused streaming must not receive updates", sawWhilePaused)

            // On foreground, it reconnects and resyncs.
            client.setForeground(true)
            val resynced = awaitUntil(15.seconds) { client.boolVariation(seed.flagKey, default = false) }
            assertTrue("resuming should reconnect and resync the flag", resynced)
        } finally {
            client.close()
        }
    }

    private suspend fun awaitUntil(timeout: Duration, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            delay(200)
        }
        return predicate()
    }

    companion object {
        private val ENABLED = System.getenv("FEATBIT_E2E") == "1"

        private lateinit var stack: FeatBitStack
        private lateinit var seed: FeatBitStack.SeedResult

        @BeforeClass
        @JvmStatic
        fun bringUp() {
            if (!ENABLED) return
            stack = FeatBitStack()
            stack.start()
            seed = stack.seed()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (ENABLED && ::stack.isInitialized) stack.close()
        }
    }
}
