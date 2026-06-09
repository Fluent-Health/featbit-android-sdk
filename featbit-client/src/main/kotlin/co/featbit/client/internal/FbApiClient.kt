package co.featbit.client.internal

import co.featbit.client.options.FBOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * Base class for the SDK's FeatBit HTTP endpoints. Centralizes the OkHttp client,
 * authentication headers, JSON configuration, and POST plumbing — the Kotlin analogue of
 * the .NET `FbApiClient`.
 *
 * @param httpClient optional client override, primarily for testing.
 */
internal abstract class FbApiClient(
    protected val options: FBOptions,
    httpClient: OkHttpClient? = null,
) : Closeable {

    private val logger = options.logger
    private val ownsClient = httpClient == null
    private val client: OkHttpClient = httpClient ?: defaultClient()

    protected data class HttpResult(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    protected suspend fun post(url: HttpUrl, payload: ByteArray): HttpResult =
        withContext(Dispatchers.IO) {
            logger.debug { "HTTP POST $url with ${payload.decodeToString()}" }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", options.secret)
                .header("User-Agent", HttpConstants.USER_AGENT)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val start = System.nanoTime()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                logger.debug {
                    "Api call took ${elapsedMs}ms, response status ${response.code}. Body: $body"
                }
                HttpResult(response.code, body)
            }
        }

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override fun close() {
        if (ownsClient) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            client.cache?.close()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 4L
        private const val READ_TIMEOUT_SECONDS = 8L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Shared JSON configuration matching the .NET SDK's "Web" defaults (camelCase, lenient). */
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
