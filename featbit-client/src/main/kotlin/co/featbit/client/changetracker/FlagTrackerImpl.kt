package co.featbit.client.changetracker

import co.featbit.client.store.FlagChangeListener
import co.featbit.client.store.FlagValueChangedEvent
import co.featbit.client.store.MemoryStore
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
        subscribers.forEach { it.onChange(event) }
        keyedSubscribers[event.key]?.forEach { it.onChange(event) }
        _flagChanges.tryEmit(event)
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
