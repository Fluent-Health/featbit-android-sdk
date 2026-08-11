package co.featbit.client.data.http

import co.featbit.client.options.FBOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

/**
 * Pins the URL-construction contract for [FBEndpoints]. The class exists to be the single
 * source of truth for FeatBit URLs, so these cases lock in:
 *  * the four streaming-URL scheme variants we accept (`ws://`, `wss://`, `http://`, `https://`),
 *  * malformed configuration failing eagerly at init (not on first network call),
 *  * the FeatBit `/streaming` path being appended exactly once.
 */
class FBEndpointsTest {

    private fun optionsWith(
        pollingUri: String = "https://app-eval.featbit.co",
        eventUri: String = "https://app-eval.featbit.co",
        streamingUri: String = "wss://app-eval.featbit.co",
    ): FBOptions = FBOptions.Builder("secret")
        .polling(pollingUri)
        .event(eventUri)
        .streaming(streamingUri)
        .build()

    @Test
    fun `latestAll and insightTrack URLs append the documented paths`() {
        val endpoints = FBEndpoints.from(optionsWith())
        assertEquals(
            "https://app-eval.featbit.co/api/public/sdk/client/latest-all",
            endpoints.latestAll.toString(),
        )
        assertEquals(
            "https://app-eval.featbit.co/api/public/insight/track",
            endpoints.insightTrack.toString(),
        )
    }

    @Test
    fun `wss streaming URI becomes https with streaming path`() {
        val endpoints = FBEndpoints.from(optionsWith(streamingUri = "wss://app-eval.featbit.co"))
        assertEquals("https://app-eval.featbit.co/streaming", endpoints.streaming.toString())
    }

    @Test
    fun `ws streaming URI becomes http with streaming path`() {
        val endpoints = FBEndpoints.from(optionsWith(streamingUri = "ws://localhost:5100"))
        assertEquals("http://localhost:5100/streaming", endpoints.streaming.toString())
    }

    @Test
    fun `https streaming URI is preserved (compat with mis-typed config)`() {
        val endpoints = FBEndpoints.from(optionsWith(streamingUri = "https://app-eval.featbit.co"))
        assertEquals("https://app-eval.featbit.co/streaming", endpoints.streaming.toString())
    }

    @Test
    fun `http streaming URI is preserved`() {
        val endpoints = FBEndpoints.from(optionsWith(streamingUri = "http://localhost:5100"))
        assertEquals("http://localhost:5100/streaming", endpoints.streaming.toString())
    }

    @Test
    fun `streaming URI with explicit path is honored, segment is appended`() {
        val endpoints = FBEndpoints.from(optionsWith(streamingUri = "wss://example.com/api"))
        // OkHttp's `addPathSegment` appends, so a custom base path is preserved.
        assertTrue(
            "got ${endpoints.streaming}",
            endpoints.streaming.toString().endsWith("/api/streaming"),
        )
    }

    @Test
    fun `malformed polling URI throws at init, not on first call`() {
        // FBOptions.Builder validates non-blankness only; URL parse-ability is FBEndpoints' job.
        val options = FBOptions.Builder("secret")
            .polling("not a url")
            .event("https://app-eval.featbit.co")
            .build()
        assertThrows(IllegalArgumentException::class.java) { FBEndpoints.from(options) }
    }

    @Test
    fun `malformed event URI throws at init`() {
        val options = FBOptions.Builder("secret")
            .polling("https://app-eval.featbit.co")
            .event("definitely::not::a::url")
            .build()
        assertThrows(IllegalArgumentException::class.java) { FBEndpoints.from(options) }
    }

    @Test
    fun `malformed streaming URI throws at init`() {
        val options = FBOptions.Builder("secret")
            .polling("https://app-eval.featbit.co")
            .event("https://app-eval.featbit.co")
            .streaming("wss://nope:notaport")
            .build()
        assertThrows(IllegalArgumentException::class.java) { FBEndpoints.from(options) }
    }
}
