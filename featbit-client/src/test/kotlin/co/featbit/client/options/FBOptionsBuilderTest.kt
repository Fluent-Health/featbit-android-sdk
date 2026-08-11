package co.featbit.client.options

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Pins the eager-validation contract on [FBOptions.Builder.build]. Misconfiguration must fail
 * at SDK init, not on the first network call — and the offline path must not require URIs.
 *
 * This is a behaviour change vs. the original (which built any options object and deferred the
 * failure to runtime); these tests lock in the new policy so it can't quietly regress.
 */
class FBOptionsBuilderTest {

    @Test
    fun `non-offline build with blank secret throws`() {
        // Builder() with no arg defaults secret to "".
        assertThrows(IllegalArgumentException::class.java) { FBOptions.Builder().build() }
    }

    @Test
    fun `non-offline build with blank polling URI throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            FBOptions.Builder("env-secret").polling("").build()
        }
    }

    @Test
    fun `non-offline build with blank event URI throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            FBOptions.Builder("env-secret").event("").build()
        }
    }

    @Test
    fun `streaming mode with blank streaming URI throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            FBOptions.Builder("env-secret").streaming("").build()
        }
    }

    @Test
    fun `offline mode does NOT require secret or URIs`() {
        // Bootstrap-only offline clients are a documented use case; validation must not
        // reject them.
        val options = FBOptions.Builder()
            .offline(true)
            .build()
        assertEquals(true, options.offline)
        assertEquals("", options.secret)
    }

    @Test
    fun `non-positive polling interval throws even in offline mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            FBOptions.Builder()
                .offline(true)
                .polling("ignored", interval = Duration.ZERO)
                .build()
        }
    }

    @Test
    fun `negative background grace period throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            FBOptions.Builder("env-secret")
                .backgroundGracePeriod((-1).milliseconds)
                .build()
        }
    }

    @Test
    fun `happy path preserves all configured values`() {
        val options = FBOptions.Builder("env-secret")
            .polling("https://eval.example.com", interval = 30.minutes)
            .event("https://eval.example.com")
            .build()
        assertEquals("env-secret", options.secret)
        assertEquals("https://eval.example.com", options.pollingUri)
        assertEquals(30.minutes, options.pollingInterval)
    }
}
