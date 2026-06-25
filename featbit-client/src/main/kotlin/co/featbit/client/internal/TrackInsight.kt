package co.featbit.client.internal

import co.featbit.client.model.Insight
import co.featbit.client.options.FBOptions
import kotlinx.coroutines.CancellationException
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
                .encodeToString(ListSerializer(Insight.serializer()), batch)
                .encodeToByteArray()
            post(endpoint, payload)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            logger.error("Exception occurred while tracking insight.", ex)
        }
    }
}
