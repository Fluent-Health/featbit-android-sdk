package co.featbit.client.internal

import co.featbit.client.model.EndUser
import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import co.featbit.client.options.FBOptions
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
) : FbApiClient(options, httpClient) {

    private val endpoint: HttpUrl = options.pollingUri.toHttpUrl().newBuilder()
        .addPathSegments(HttpConstants.LATEST_ALL_PATH)
        .build()

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

        val featureFlags = json.parseToJsonElement(result.body)
            .jsonObject["data"]?.jsonObject
            ?.get("featureFlags")
            ?.let { json.decodeFromJsonElement(ListSerializer(FeatureFlag.serializer()), it) }
            ?: emptyList()

        return GetUserFlagsResponse.ok(featureFlags)
    }
}
