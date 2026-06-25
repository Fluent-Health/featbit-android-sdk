package co.featbit.client.data.http

import co.featbit.client.wire.EndUser
import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import co.featbit.client.options.FBOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient

/**
 * Outcome of a `latest-all` poll. A 401 is fatal (bad secret) and stops polling; any other
 * non-2xx is a transient error that is retried on the next interval.
 */
internal data class GetUserFlagsResponse(
    val statusCode: Int,
    val flags: List<FeatureFlag>,
) {
    val isError: Boolean get() = statusCode != 200
    val isFatal: Boolean get() = statusCode == 401

    companion object {
        fun ok(flags: List<FeatureFlag>): GetUserFlagsResponse = GetUserFlagsResponse(200, flags)
        fun error(statusCode: Int): GetUserFlagsResponse = GetUserFlagsResponse(statusCode, emptyList())
    }
}

/**
 * Fetches the latest feature flags for a user from the FeatBit evaluation server.
 * POSTs the end-user payload to `api/public/sdk/client/latest-all?timestamp=...`.
 */
internal class GetUserFlags(
    options: FBOptions,
    user: FBUser,
    httpClient: OkHttpClient? = null,
    endpoints: FBEndpoints = FBEndpoints.from(options),
) : FbApiClient(options, httpClient) {

    private val endpoint = endpoints.latestAll

    private val payload: ByteArray =
        json.encodeToString(EndUser.serializer(), user.toEndUser()).encodeToByteArray()

    suspend fun run(timestamp: Long): GetUserFlagsResponse {
        val url = endpoint.newBuilder()
            .addQueryParameter("timestamp", timestamp.toString())
            .build()

        val result = post(url, payload)
        if (!result.isSuccessful) {
            return GetUserFlagsResponse.error(result.code)
        }
        if (result.body.isBlank()) {
            return GetUserFlagsResponse.ok(emptyList())
        }

        // Wire shape: `{"data": {"featureFlags": [...]}}`. We deserialize through a typed
        // envelope in a single pass — the prior implementation went body → JsonElement AST →
        // navigate → decodeFromJsonElement, allocating a full intermediate tree plus
        // re-walking it. One pass eliminates the tree allocation entirely.
        //
        // Shape mismatch still throws (via SerializationException), caught in `safePoll`
        // and logged. Quietly returning emptyList() would be indistinguishable from "user has
        // no flags" and would silently serve defaults forever.
        val envelope = json.decodeFromString(LatestAllEnvelope.serializer(), result.body)
        return GetUserFlagsResponse.ok(envelope.data?.featureFlags ?: emptyList())
    }

    @Serializable
    private data class LatestAllEnvelope(val data: LatestAllData? = null)

    @Serializable
    private data class LatestAllData(val featureFlags: List<FeatureFlag> = emptyList())
}
