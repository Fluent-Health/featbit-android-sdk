package co.featbit.client.data.http

import co.featbit.client.wire.EndUser
import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import co.featbit.client.options.FBOptions
import kotlinx.serialization.Serializable
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
        // Field nullability mirrors the old AST behavior exactly:
        //   * `data` field absent       → defaults to LatestAllData() (empty flags). Matches
        //                                 the old `?.jsonObject → null → return ok(emptyList())`.
        //   * `data` field is JSON null → throws SerializationException because `data` is
        //                                 non-nullable. The old code threw IllegalArgumentException
        //                                 on `JsonNull.jsonObject`; either way `safePoll`
        //                                 catches and logs.
        //   * `data` wrong type         → SerializationException.
        //   * `featureFlags` wrong type → SerializationException.
        //
        // Quietly returning `emptyList()` on a malformed payload would be indistinguishable
        // from "user has no flags" and would make the SDK silently serve defaults forever —
        // this is the exact failure mode the throw-on-malformed contract guards against.
        //
        // Empirical note (kotlinx-serialization 1.6.3 + `explicitNulls=false`): a nullable
        // field WITH a null default coerces JSON null → Kotlin null without throwing. That
        // would re-introduce the silent-empty bug. Keep `data` non-nullable.
        val envelope = json.decodeFromString(LatestAllEnvelope.serializer(), result.body)
        return GetUserFlagsResponse.ok(envelope.data.featureFlags)
    }

    @Serializable
    private data class LatestAllEnvelope(val data: LatestAllData = LatestAllData())

    @Serializable
    private data class LatestAllData(val featureFlags: List<FeatureFlag> = emptyList())
}
