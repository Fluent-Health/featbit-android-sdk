package co.featbit.client.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.lifecycle.Startables
import org.testcontainers.utility.MountableFile
import java.time.Duration
import java.util.UUID

/**
 * Spins up a real FeatBit stack (Postgres + api-server + evaluation-server) with Testcontainers
 * and seeds a known feature flag, so the SDK can be exercised against a real evaluation server.
 *
 * Mirrors FeatBit's official Postgres-backed docker-compose. The management API flow
 * (login → discover workspace/org → onboarding → create flag) was derived empirically against
 * the live server and is documented inline.
 */
class FeatBitStack : AutoCloseable {

    private class KContainer(image: String) : GenericContainer<KContainer>(image)

    private val network = Network.newNetwork()
    private val pgConn =
        "Host=postgresql;Port=5432;Username=postgres;Password=please_change_me;Database=featbit"

    private val postgres = KContainer("postgres:15.10")
        .withNetwork(network)
        .withNetworkAliases("postgresql")
        .withEnv("POSTGRES_USER", "postgres")
        .withEnv("POSTGRES_PASSWORD", "please_change_me")
        .withStartupTimeout(Duration.ofSeconds(120))
        .waitingFor(
            Wait.forLogMessage(".*database system is ready to accept connections.*", 2),
        )
        .apply {
            // Postgres runs *.sql in /docker-entrypoint-initdb.d alphabetically (v0.0.0 first).
            INIT_SCRIPTS.forEach { name ->
                withCopyFileToContainer(
                    MountableFile.forClasspathResource("e2e/initdb/$name"),
                    "/docker-entrypoint-initdb.d/$name",
                )
            }
        }

    private val apiServer = KContainer("featbit/featbit-api-server:latest")
        .withNetwork(network)
        .withEnv("DbProvider", "Postgres")
        .withEnv("MqProvider", "Postgres")
        .withEnv("CacheProvider", "None")
        .withEnv("Postgres__ConnectionString", pgConn)
        .withEnv("OLAP__ServiceHost", "http://da-server")
        .withEnv("Jwt__Algorithm", "HS256")
        .withEnv("Jwt__Key", "please_change_me_to_a_secure_secret_key")
        .withExposedPorts(API_PORT)
        .dependsOn(postgres)
        .waitingFor(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofSeconds(240))

    private val evaluationServer = KContainer("featbit/featbit-evaluation-server:latest")
        .withNetwork(network)
        .withEnv("DbProvider", "Postgres")
        .withEnv("MqProvider", "Postgres")
        .withEnv("CacheProvider", "None")
        .withEnv("Postgres__ConnectionString", pgConn)
        .withExposedPorts(EVAL_PORT)
        .dependsOn(postgres)
        .waitingFor(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofSeconds(240))

    private val http = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(20))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var apiBase: String

    // Captured during seeding so the test can mutate the flag server-side.
    private lateinit var token: String
    private lateinit var workspaceId: String
    private lateinit var organizationId: String
    private lateinit var envId: String

    data class SeedResult(
        val evaluationBaseUrl: String,
        val clientSecret: String,
        val flagKey: String,
    )

    fun start() {
        Startables.deepStart(listOf(apiServer, evaluationServer)).join()
        apiBase = "http://${apiServer.host}:${apiServer.getMappedPort(API_PORT)}"
    }

    /** Logs in, discovers the workspace/org, onboards a project, and creates an enabled boolean flag. */
    fun seed(): SeedResult {
        token = retryForToken()
        workspaceId = firstId(get("/api/v1/user/workspaces"))
        organizationId = firstId(get("/api/v1/organizations", workspace = workspaceId))

        post(
            "/api/v1/organizations/onboarding",
            """{"organizationName":"playground","organizationKey":"playground",
                "projectName":"e2e","projectKey":"e2e","environments":["prod"]}""",
            workspace = workspaceId,
            organization = organizationId,
        )

        val (env, secret) = readEnvAndClientSecret()
        envId = env
        createBooleanFlag(FLAG_KEY)

        val evalBase = "http://${evaluationServer.host}:${evaluationServer.getMappedPort(EVAL_PORT)}"
        return SeedResult(evalBase, secret, FLAG_KEY)
    }

    /** Toggles the seeded flag on/off via the management API (drives change-detection tests). */
    fun toggleFlag(enabled: Boolean) {
        put(
            "/api/v1/envs/$envId/feature-flags/$FLAG_KEY/toggle/$enabled",
            workspace = workspaceId,
            organization = organizationId,
        )
    }

    override fun close() {
        runCatching { evaluationServer.stop() }
        runCatching { apiServer.stop() }
        runCatching { postgres.stop() }
        runCatching { network.close() }
    }

    // --- management API helpers -------------------------------------------------------------

    private fun retryForToken(): String {
        val deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos()
        var last: Exception? = null
        while (System.nanoTime() < deadline) {
            try {
                val body = post(
                    "/api/v1/identity/login-by-email",
                    """{"email":"test@featbit.com","password":"123456"}""",
                )
                return json.parseToJsonElement(body).data().jsonObject["token"]!!.jsonPrimitive.content
            } catch (e: Exception) {
                last = e
                Thread.sleep(3000)
            }
        }
        throw IllegalStateException("api-server did not become ready in time", last)
    }

    private fun createBooleanFlag(key: String) {
        val trueId = UUID.randomUUID().toString()
        val falseId = UUID.randomUUID().toString()
        post(
            "/api/v1/envs/$envId/feature-flags",
            """{"name":"$key","key":"$key","isEnabled":true,"description":"",
                "variationType":"boolean",
                "variations":[{"id":"$trueId","name":"true","value":"true"},
                              {"id":"$falseId","name":"false","value":"false"}],
                "enabledVariationId":"$trueId","disabledVariationId":"$falseId","tags":[]}""",
            workspace = workspaceId,
            organization = organizationId,
        )
    }

    private fun readEnvAndClientSecret(): Pair<String, String> {
        val data = json.parseToJsonElement(
            get("/api/v1/projects", workspace = workspaceId, organization = organizationId),
        ).data() as JsonArray
        val env = data.first().jsonObject["environments"]!!.jsonArray.first().jsonObject
        val envId = env["id"]!!.jsonPrimitive.content
        val clientSecret = env["secrets"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "client" }["value"]!!.jsonPrimitive.content
        return envId to clientSecret
    }

    private fun firstId(responseBody: String): String =
        (json.parseToJsonElement(responseBody).data() as JsonArray)
            .first().jsonObject["id"]!!.jsonPrimitive.content

    private fun kotlinx.serialization.json.JsonElement.data(): kotlinx.serialization.json.JsonElement =
        jsonObject["data"]!!

    private fun get(path: String, workspace: String? = null, organization: String? = null): String =
        execute(request(path, workspace, organization).get().build())

    private fun post(
        path: String,
        body: String,
        workspace: String? = null,
        organization: String? = null,
    ): String = execute(
        request(path, workspace, organization)
            .post(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
    )

    private fun put(path: String, workspace: String? = null, organization: String? = null): String =
        execute(
            request(path, workspace, organization)
                .put(ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)).build(),
        )

    private fun request(path: String, workspace: String?, organization: String?): Request.Builder {
        val b = Request.Builder().url("$apiBase$path").header("Content-Type", "application/json")
        if (::token.isInitialized) b.header("Authorization", "Bearer $token")
        workspace?.let { b.header("Workspace", it) }
        organization?.let { b.header("Organization", it) }
        return b
    }

    private fun execute(request: Request): String =
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "HTTP ${response.code} for ${request.url}: $body" }
            check(json.parseToJsonElement(body).jsonObject["success"]?.jsonPrimitive?.content != "false") {
                "FeatBit API error for ${request.url}: $body"
            }
            body
        }

    companion object {
        const val FLAG_KEY = "e2e-bool-flag"
        private const val API_PORT = 5000
        private const val EVAL_PORT = 5100
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val INIT_SCRIPTS = listOf(
            "v0.0.0.sql", "v5.0.4.sql", "v5.0.5.sql", "v5.1.0.sql",
            "v5.2.0.sql", "v5.2.1.sql", "v5.3.0.sql", "v5.3.2.sql", "v5.4.0.sql",
        )
    }
}
