package co.featbit.example

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import co.featbit.client.FBClient
import co.featbit.client.FBClientImpl
import co.featbit.client.model.FBUser
import co.featbit.client.options.FBOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Minimal demonstration of the FeatBit Kotlin client SDK — a port of the .NET ConsoleApp
 * example. Replace [SECRET] and the URLs with your environment values before running.
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var output: TextView
    private var client: FBClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply { setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(output) })

        scope.launch { runDemo() }
    }

    private suspend fun runDemo() {
        if (SECRET.isBlank()) {
            log("Set SECRET in MainActivity.kt to your FeatBit env secret first.")
            return
        }

        val options = FBOptions.Builder(SECRET)
            .polling(EVAL_URL, interval = 10.seconds)
            .event(EVAL_URL)
            .build()

        val initialUser = FBUser.builder("tester-id")
            .name("tester")
            .custom("role", "developer")
            .build()

        val fbClient = withContext(Dispatchers.IO) { FBClientImpl(options, initialUser) }
        client = fbClient

        val started = fbClient.start(timeout = 3.seconds)
        log(if (started) "FBClient initialized." else "FBClient failed to initialize; using fallbacks.")

        // Subscribe to flag changes (parity with .NET FlagTracker).
        fbClient.flagTracker.subscribe { e ->
            log("Flag '${e.key}' changed from '${e.oldValue}' to '${e.newValue}'")
        }

        // Switch to an authenticated user.
        val authedUser = FBUser.builder("a-unique-key-of-bob").name("bob").custom("country", "FR").build()
        fbClient.identify(authedUser)

        // Evaluate a flag.
        val flagKey = "game-runner"
        val detail = fbClient.boolVariationDetail(flagKey, default = false)
        log("Flag '$flagKey' = ${detail.value} (reason: ${detail.reason})")
    }

    private fun log(message: String) {
        runOnUiThread { output.append(message + "\n") }
    }

    override fun onDestroy() {
        client?.close()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val SECRET = "" // <-- replace with your FeatBit environment secret
        const val EVAL_URL = "https://app-eval.featbit.co"
    }
}
