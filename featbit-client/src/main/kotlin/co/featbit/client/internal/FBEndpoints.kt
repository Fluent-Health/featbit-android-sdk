package co.featbit.client.internal

import co.featbit.client.options.FBOptions
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Resolves every FeatBit endpoint the SDK talks to from an [FBOptions] instance.
 *
 * Centralizing URL construction here removes the three near-identical
 * `pollingUri.toHttpUrl().newBuilder().addPathSegments(...)` snippets that previously
 * lived inside [GetUserFlags], [HttpTrackInsight], and the streaming synchronizer.
 *
 * URLs are parsed eagerly so a malformed configuration fails at SDK init rather than on
 * the first network call.
 */
internal class FBEndpoints private constructor(
    val latestAll: HttpUrl,
    val insightTrack: HttpUrl,
    val streaming: HttpUrl,
) {
    companion object {
        fun from(options: FBOptions): FBEndpoints {
            val latestAll = options.pollingUri.toHttpUrl().newBuilder()
                .addPathSegments(HttpConstants.LATEST_ALL_PATH)
                .build()
            val insightTrack = options.eventUri.toHttpUrl().newBuilder()
                .addPathSegments(HttpConstants.INSIGHT_TRACK_PATH)
                .build()
            val streaming = options.streamingUri.toStreamingHttpUrl()
            return FBEndpoints(latestAll, insightTrack, streaming)
        }

        /** Accepts `ws(s)://` (or `http(s)://`) and returns the `/streaming` HTTP(S) URL OkHttp uses. */
        private fun String.toStreamingHttpUrl(): HttpUrl =
            replaceFirst(Regex("^ws"), "http").toHttpUrl().newBuilder()
                .addPathSegment("streaming")
                .build()
    }
}
