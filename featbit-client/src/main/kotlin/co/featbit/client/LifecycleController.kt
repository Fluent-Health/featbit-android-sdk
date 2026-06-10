package co.featbit.client

import co.featbit.client.datasynchronizer.DataSynchronizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives a [DataSynchronizer] from app-lifecycle and network signals: the synchronizer is kept
 * "active" only while the app is foregrounded **and** the network is available. Transitions to
 * inactive are debounced by [graceMs] so brief app-switches or network blips don't tear the
 * connection down. All transitions are serialized on [scope].
 *
 * Signals default to active (foreground + online), so a client whose hooks are never called
 * behaves exactly as before (always-on synchronization).
 */
internal class LifecycleController(
    private val scope: CoroutineScope,
    private val graceMs: Long,
    private val synchronizer: () -> DataSynchronizer,
) {
    private val mutex = Mutex()
    private var foreground = true
    private var online = true
    private var active = true
    private var pauseJob: Job? = null

    fun onForegroundChanged(value: Boolean) = submit { foreground = value }

    fun onNetworkChanged(value: Boolean) = submit { online = value }

    private fun submit(change: () -> Unit) {
        scope.launch {
            mutex.withLock {
                change()
                if (foreground && online) {
                    // Became (or stayed) active: cancel any pending pause; resume if paused.
                    pauseJob?.cancel()
                    pauseJob = null
                    if (!active) {
                        active = true
                        synchronizer().resume()
                    }
                } else if (active && pauseJob == null) {
                    // Became inactive: pause after a grace period unless we become active again.
                    pauseJob = scope.launch {
                        delay(graceMs)
                        mutex.withLock {
                            if (!(foreground && online)) {
                                active = false
                                synchronizer().pause()
                            }
                            pauseJob = null
                        }
                    }
                }
            }
        }
    }
}
