package co.featbit.client

import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import co.featbit.client.options.FBOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FBClientImplTest {

    private val user = FBUser.builder("u1").name("bob").build()

    private fun offlineClientWith(vararg flags: FeatureFlag): FBClient {
        val options = FBOptions.Builder()
            .offline(true)
            .bootstrap(flags.toList())
            .build()
        return FBClientImpl(options, user)
    }

    @Test
    fun `offline client with bootstrap is initialized and evaluates flags`() = runBlocking {
        val client = offlineClientWith(
            FeatureFlag(id = "bool-flag", variation = "true", matchReason = "fallthrough"),
            FeatureFlag(id = "str-flag", variation = "hello"),
        )

        assertTrue(client.start())
        assertTrue(client.initialized)

        assertTrue(client.boolVariation("bool-flag"))
        assertEquals("hello", client.stringVariation("str-flag"))

        val detail = client.boolVariationDetail("bool-flag")
        assertTrue(detail.value)
        assertEquals("fallthrough", detail.reason)

        client.close()
    }

    @Test
    fun `unknown flag returns default with flag-not-found reason`() = runBlocking {
        val client = offlineClientWith(FeatureFlag(id = "known", variation = "true"))
        client.start()

        assertFalse(client.boolVariation("unknown", default = false))
        assertEquals("flag not found", client.boolVariationDetail("unknown").reason)

        client.close()
    }

    @Test
    fun `type mismatch returns default`() = runBlocking {
        val client = offlineClientWith(FeatureFlag(id = "weird", variation = "not-a-bool"))
        client.start()

        assertFalse(client.boolVariation("weird", default = false))
        assertEquals("type mismatch", client.boolVariationDetail("weird").reason)

        client.close()
    }

    @Test
    fun `not-ready client without bootstrap returns client-not-ready`() {
        // Non-offline, never started: data synchronizer reports not initialized.
        val options = FBOptions.Builder("secret").build()
        val client = FBClientImpl(options, user)

        assertFalse(client.initialized)
        val detail = client.stringVariationDetail("any", default = "fallback")
        assertEquals("client not ready", detail.reason)
        assertEquals("fallback", detail.value)

        client.close()
    }

    @Test
    fun `allFlags returns bootstrap snapshot`() {
        val client = offlineClientWith(
            FeatureFlag(id = "a", variation = "1"),
            FeatureFlag(id = "b", variation = "2"),
        )
        val all = client.allFlags()
        assertEquals(setOf("a", "b"), all.keys)
        assertEquals("1", all["a"]?.variation)
        client.close()
    }
}
