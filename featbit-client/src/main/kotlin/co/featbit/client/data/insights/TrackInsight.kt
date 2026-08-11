package co.featbit.client.data.insights

import co.featbit.client.data.http.FBEndpoints
import co.featbit.client.data.http.FbApiClient
import co.featbit.client.wire.Insight
import co.featbit.client.options.FBOptions
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient

/** Sends analytics insight events to FeatBit. */
internal interface TrackInsight {
    /** Posts the given [batch] as a single payload. The list must be non-empty. */
    suspend fun run(batch: List<Insight>)
    fun close()
}

/** Convenience overload — most callers send one insight at a time. */
internal suspend fun TrackInsight.run(insight: Insight): Unit = run(listOf(insight))

/** No-op tracker used in offline mode. */
internal data object NoopTrackInsight : TrackInsight {
    override suspend fun run(batch: List<Insight>): Unit = Unit
    override fun close(): Unit = Unit
}

/** Default tracker: POSTs an insight array to `api/public/insight/track`. */
internal class HttpTrackInsight(
    options: FBOptions,
    httpClient: OkHttpClient? = null,
    endpoints: FBEndpoints = FBEndpoints.from(options),
) : FbApiClient(options, httpClient), TrackInsight {

    private val logger = options.logger
    private val endpoint = endpoints.insightTrack

    override suspend fun run(batch: List<Insight>) {
        if (batch.isEmpty()) return
        try {
            val payload = json
                .encodeToString(INSIGHT_LIST_SERIALIZER, batch)
                .encodeToByteArray()
            post(endpoint, payload)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            logger.error("Exception occurred while tracking insight.", ex)
        }
    }

    private companion object {
        // ListSerializer wraps an element serializer; building one per send is pure overhead
        // since the type never changes. Cache the singleton.
        private val INSIGHT_LIST_SERIALIZER: KSerializer<List<Insight>> =
            ListSerializer(Insight.serializer())
    }
}
