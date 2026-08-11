package co.featbit.client.app

import co.featbit.client.changetracker.FlagTracker
import co.featbit.client.domain.MemoryStore
import co.featbit.client.store.FlagChangeListener
import co.featbit.client.store.FlagValueChangedEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default [FlagTracker]. Registers a single [FlagChangeListener] with the [store] and
 * fans every change out to global subscribers, key-specific subscribers, and the
 * [flagChanges] flow.
 */
internal class FlagTrackerImpl(
    private val store: MemoryStore,
) : FlagTracker, Closeable {

    private val subscribers = CopyOnWriteArrayList<FlagChangeListener>()
    private val keyedSubscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<FlagChangeListener>>()

    private val _flagChanges = MutableSharedFlow<FlagValueChangedEvent>(extraBufferCapacity = 64)
    override val flagChanges: Flow<FlagValueChangedEvent> = _flagChanges.asSharedFlow()

    private val storeListener = FlagChangeListener { event -> dispatch(event) }

    init {
        store.addChangeListener(storeListener)
    }

    private fun dispatch(event: FlagValueChangedEvent) {
        // Each subscriber callback is isolated in its own try/catch so a single throwing
        // subscriber cannot starve subsequent subscribers from receiving the event. Without
        // this, `CopyOnWriteArrayList.forEach` halts on the first exception — silently losing
        // delivery to every later-registered subscriber for that event. That's a data-loss
        // bug for a callback-style API where one consumer can't be allowed to break others.
        subscribers.forEach { safeNotify(it, event) }
        keyedSubscribers[event.key]?.forEach { safeNotify(it, event) }
        _flagChanges.tryEmit(event)
    }

    private fun safeNotify(listener: FlagChangeListener, event: FlagValueChangedEvent) {
        try {
            listener.onChange(event)
        } catch (t: Throwable) {
            // No injected logger here; fall back to stderr like `DefaultLogger.write` does
            // when android.util.Log isn't available. Swallow the throw so iteration continues.
            System.err.println(
                "[FeatBit] FlagTracker subscriber threw for event ${event.key}: ${t.message}",
            )
            t.printStackTrace(System.err)
        }
    }

    override fun subscribe(listener: FlagChangeListener) {
        subscribers.addIfAbsent(listener)
    }

    override fun subscribe(key: String, listener: FlagChangeListener) {
        keyedSubscribers.getOrPut(key) { CopyOnWriteArrayList() }.addIfAbsent(listener)
    }

    override fun unsubscribe(listener: FlagChangeListener) {
        subscribers.remove(listener)
    }

    override fun unsubscribe(key: String, listener: FlagChangeListener) {
        keyedSubscribers[key]?.remove(listener)
    }

    override fun close() {
        subscribers.clear()
        keyedSubscribers.clear()
        store.removeChangeListener(storeListener)
    }
}
