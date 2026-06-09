package co.featbit.client.e2e

import co.featbit.client.FBClientImpl
import co.featbit.client.model.FBUser
import co.featbit.client.options.FBOptions
import co.featbit.client.store.FlagValueChangedEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Full-stack end-to-end test: a real [FBClientImpl] driven against a real FeatBit evaluation
 * server (stood up via Testcontainers in [FeatBitStack]).
 *
 * Gated by the `FEATBIT_E2E=1` environment variable so it never runs in the default unit-test
 * pass (which must not require Docker). CI sets `FEATBIT_E2E=1` on a Docker-capable runner.
 */
class FeatBitE2ETest {

    @Before
    fun gate() {
        Assume.assumeTrue("Set FEATBIT_E2E=1 (and have Docker) to run E2E tests", ENABLED)
    }

    @Test
    fun `start, evaluate seeded flag, observe server-side change, and identify`() = runBlocking {
        val options = FBOptions.Builder(seed.clientSecret)
            .polling(seed.evaluationBaseUrl, interval = 1.seconds)
            .event(seed.evaluationBaseUrl)
            .build()

        val client = FBClientImpl(options, FBUser.builder("e2e-user").name("e2e").build())
        val changes = CopyOnWriteArrayList<FlagValueChangedEvent>()

        try {
            assertTrue("client should start within timeout", client.start(15.seconds))
            assertTrue("client should be initialized", client.initialized)

            // Seeded flag is enabled -> serves the "true" variation via the real eval server.
            val detail = client.boolVariationDetail(seed.flagKey, default = false)
            assertTrue("seeded flag should evaluate to true", detail.value)
            assertEquals("default", detail.reason)

            // Subscribe, then flip the flag off server-side; the polling SDK must observe it.
            client.flagTracker.subscribe(seed.flagKey) { changes.add(it) }
            stack.toggleFlag(enabled = false)

            val flipped = awaitUntil(20.seconds) { !client.boolVariation(seed.flagKey, default = true) }
            assertTrue("SDK should observe the server-side flag change via polling", flipped)
            assertTrue("a flag-change event should have fired", changes.isNotEmpty())
            assertEquals("false", changes.last().newValue)

            // Switching the evaluation user still works end-to-end.
            assertTrue(
                "identify should succeed",
                client.identify(FBUser.builder("another-user").name("another").build(), 15.seconds),
            )
        } finally {
            client.close()
        }
    }

    private suspend fun awaitUntil(timeout: Duration, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            delay(250)
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
