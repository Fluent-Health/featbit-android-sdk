package co.featbit.client.internal

import co.featbit.client.model.Insight
import co.featbit.client.options.FBOptions
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Sends analytics insight events to FeatBit. */
internal interface TrackInsight {
    suspend fun run(insight: Insight)
    fun close()
}

/** No-op tracker used in offline mode. */
internal object NoopTrackInsight : TrackInsight {
    override suspend fun run(insight: Insight) {}
    override fun close() {}
}

/** Default tracker: POSTs a single-element insight array to `api/public/insight/track`. */
internal class HttpTrackInsight(
    options: FBOptions,
    httpClient: OkHttpClient? = null,
) : FbApiClient(options, httpClient), TrackInsight {

    private val logger = options.logger

    private val endpoint: HttpUrl = options.eventUri.toHttpUrl().newBuilder()
        .addPathSegments(HttpConstants.INSIGHT_TRACK_PATH)
        .build()

    override suspend fun run(insight: Insight) {
        try {
            val payload = json
                .encodeToString(ListSerializer(Insight.serializer()), listOf(insight))
                .encodeToByteArray()
            post(endpoint, payload)
        } catch (ex: Exception) {
            logger.error("Exception occurred while tracking insight.", ex)
        }
    }
}
