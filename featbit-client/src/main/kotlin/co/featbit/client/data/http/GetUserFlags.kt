package co.featbit.client.data.http

import co.featbit.client.model.EndUser
import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import co.featbit.client.options.FBOptions
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

        // Wire shape: `{"data": {"featureFlags": [...]}}`. We let `.jsonObject` / `.jsonArray`
        // throw on a shape mismatch — `safePoll` catches and logs it as an error. Quietly
        // returning `emptyList()` on a malformed payload would be indistinguishable from
        // "user has no flags" and would silently serve defaults forever.
        val data = json.parseToJsonElement(result.body).jsonObject["data"]?.jsonObject
        val featureFlagsArr = data?.get("featureFlags")?.jsonArray ?: return GetUserFlagsResponse.ok(emptyList())
        val featureFlags = json.decodeFromJsonElement(ListSerializer(FeatureFlag.serializer()), featureFlagsArr)

        return GetUserFlagsResponse.ok(featureFlags)
    }
}
