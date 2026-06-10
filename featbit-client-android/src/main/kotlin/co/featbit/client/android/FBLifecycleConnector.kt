package co.featbit.client.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import co.featbit.client.FBClient

/**
 * Wires Android app-lifecycle and network signals into an [FBClient]. With this connector,
 * a streaming client drops its WebSocket shortly after the app is backgrounded or goes offline,
 * and re-establishes it (with an immediate resync) on foreground / when connectivity returns.
 *
 * This is the optional Android glue for the platform-agnostic hooks on [FBClient]
 * ([FBClient.setForeground] / [FBClient.setNetworkAvailable]); apps that don't use it pay no
 * extra dependency on the core module.
 *
 * Usage, e.g. in `Application.onCreate` (call [start] on the main thread):
 * ```
 * class App : Application() {
 *     private lateinit var connector: FBLifecycleConnector
 *     override fun onCreate() {
 *         super.onCreate()
 *         connector = FBLifecycleConnector(this, fbClient).also { it.start() }
 *     }
 * }
 * ```
 */
public class FBLifecycleConnector(
    context: Context,
    private val client: FBClient,
) : DefaultLifecycleObserver {

    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = client.setNetworkAvailable(true)
        override fun onLost(network: Network) = client.setNetworkAvailable(false)
    }

    private var started = false

    /** Registers lifecycle + network observers. Call on the main thread (e.g. Application.onCreate). */
    public fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    /** Unregisters observers. Call when the client is no longer used. */
    public fun stop() {
        if (!started) return
        started = false
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    override fun onStart(owner: LifecycleOwner): Unit = client.setForeground(true)

    override fun onStop(owner: LifecycleOwner): Unit = client.setForeground(false)
}
