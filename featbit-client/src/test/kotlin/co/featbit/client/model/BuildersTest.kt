package co.featbit.client.model

import co.featbit.client.options.DataSyncMode
import co.featbit.client.wire.CustomizedProperty
import co.featbit.client.options.FBOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class BuildersTest {

    @Test
    fun `user builder sets key, name and custom`() {
        val user = FBUser.builder("k1").name("bob").custom("country", "FR").build()
        assertEquals("k1", user.key)
        assertEquals("bob", user.name)
        assertEquals(mapOf("country" to "FR"), user.custom)
    }

    @Test
    fun `blank name is ignored, blank custom key throws`() {
        val user = FBUser.builder("k1").name("   ").build()
        assertEquals("", user.name)
        assertThrows(IllegalArgumentException::class.java) {
            FBUser.builder("k1").custom("  ", "v")
        }
    }

    @Test
    fun `toEndUser maps custom attributes`() {
        val endUser = FBUser.builder("k1").name("bob").custom("a", "1").build().toEndUser()
        assertEquals("k1", endUser.keyId)
        assertEquals("bob", endUser.name)
        assertEquals(listOf(CustomizedProperty("a", "1")), endUser.customizedProperties)
    }

    /**
     * Contract: `FBUser.toEndUser()` returns the *same* EndUser instance on every call. The
     * user is immutable, so its wire form is too — building it once at construction and
     * caching it eliminates a per-evaluation allocation of EndUser + the
     * `CustomizedProperty` list + N entries (for an N-custom-attribute user).
     *
     * Mutation that would fail this:
     *   * Reverting `private val endUser` to compute-on-call (the prior `customizedProperties
     *     = custom.map { ... }` per-call mapping) — every invocation would yield a fresh
     *     EndUser, failing `assertSame`.
     *   * Swapping the cache to `lazy { ... }` would still pass (one instance, computed
     *     once). That's acceptable — the contract is "stable identity" not "constructor
     *     eager".
     */
    @Test
    fun `toEndUser returns same cached instance across many calls`() {
        val user = FBUser.builder("k1")
            .name("bob")
            .custom("country", "FR")
            .custom("plan", "pro")
            .build()

        val first = user.toEndUser()
        repeat(1_000) {
            assertSame(
                "toEndUser must be cached — repeated calls return the same EndUser identity",
                first,
                user.toEndUser(),
            )
        }
        // Sanity: the cached instance carries the correct data, not just a stable identity.
        assertEquals("k1", first.keyId)
        assertEquals("bob", first.name)
        assertEquals(2, first.customizedProperties.size)
    }

    @Test
    fun `options builder defaults and overrides`() {
        val defaults = FBOptions.Builder("secret").build()
        assertFalse(defaults.offline)
        assertEquals(DataSyncMode.Polling, defaults.dataSyncMode)
        assertEquals("secret", defaults.secret)

        val custom = FBOptions.Builder("secret")
            .polling("https://eval.example.com", interval = 10.seconds)
            .event("https://events.example.com")
            .offline(true)
            .bootstrap(listOf(FeatureFlag(id = "f", variation = "true")))
            .build()
        assertTrue(custom.offline)
        assertEquals(10.seconds, custom.pollingInterval)
        assertEquals("https://eval.example.com", custom.pollingUri)
        assertEquals(1, custom.bootstrap.size)
    }
}
