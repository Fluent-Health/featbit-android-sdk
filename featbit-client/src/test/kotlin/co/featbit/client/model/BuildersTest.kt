package co.featbit.client.model

import co.featbit.client.options.DataSyncMode
import co.featbit.client.options.FBOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
