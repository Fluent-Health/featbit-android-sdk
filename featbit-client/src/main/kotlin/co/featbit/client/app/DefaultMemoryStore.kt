package co.featbit.client.app

import co.featbit.client.domain.MemoryStore
import co.featbit.client.model.FeatureFlag
import co.featbit.client.store.FlagChangeListener
import co.featbit.client.store.FlagValueChangedEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default [MemoryStore] backed by a [ConcurrentHashMap].
 *
 * Reads are lock-free. Writes are serialized under a single monitor so that the
 * "compute change event then store" sequence is atomic, matching the .NET
 * `DefaultMemoryStore`. Change listeners are notified outside the write lock.
 */
public class DefaultMemoryStore(
    bootstrap: List<FeatureFlag> = emptyList(),
) : MemoryStore {

    private val items: ConcurrentHashMap<String, FeatureFlag> = ConcurrentHashMap()
    private val listeners: CopyOnWriteArrayList<FlagChangeListener> = CopyOnWriteArrayList()
    private val writeLock = Any()

    init {
        bootstrap.forEach { items[it.id] = it }
    }

    override fun get(id: String): FeatureFlag? = items[id]

    override fun getAll(): Collection<FeatureFlag> = items.values.toList()

    override fun upsert(flag: FeatureFlag) {
        val event: FlagValueChangedEvent? = synchronized(writeLock) {
            computeEventAndStore(flag)
        }

        if (event != null) {
            listeners.forEach { it.onChange(event) }
        }
    }

    /**
     * Bulk upsert under a single monitor — used by the polling path which receives an N-flag
     * snapshot per response. The pre-existing per-flag [upsert] enters the writeLock N times
     * (N monitor enters + N exits); this variant enters once. Change events are still raised
     * one-per-flag, *outside* the lock, so listener latency cannot stall the writer thread.
     *
     * Listener-throw semantics: a `forEach` callback that throws unwinds out of both the
     * inner `listeners.forEach` AND the outer `for (event in events)` loop, so subsequent
     * events for the batch will not fire listeners. This matches the legacy
     * `response.flags.forEach { store.upsert(it) }` polling-caller behavior (same unwind),
     * but with a directional improvement: under the legacy path, a listener throwing on
     * event K aborted upserts K+1..N as well; under this bulk path, ALL writes are committed
     * under the lock BEFORE any listener fires, so the store reaches its post-batch state
     * regardless of listener throws downstream. Listeners that need exception isolation
     * should wrap their own onChange bodies.
     */
    override fun upsertAll(flags: Collection<FeatureFlag>) {
        if (flags.isEmpty()) return
        // Collect events under the write lock so the "compute change event then store"
        // sequence stays atomic per flag, matching single-upsert semantics.
        val events: List<FlagValueChangedEvent> = synchronized(writeLock) {
            val collected = ArrayList<FlagValueChangedEvent>(flags.size)
            for (flag in flags) {
                val event = computeEventAndStore(flag)
                if (event != null) collected += event
            }
            collected
        }

        if (events.isEmpty() || listeners.isEmpty()) return
        // forEach listener × forEach event would be O(L*E) monitor-light callbacks; we accept
        // that cost rather than re-snapshotting listeners per event.
        for (event in events) {
            listeners.forEach { it.onChange(event) }
        }
    }

    private fun computeEventAndStore(flag: FeatureFlag): FlagValueChangedEvent? {
        val existing = items[flag.id]
        val change = when {
            existing == null -> FlagValueChangedEvent(flag.id, null, flag.variation)
            existing.variation != flag.variation ->
                FlagValueChangedEvent(flag.id, existing.variation, flag.variation)
            else -> null
        }
        items[flag.id] = flag
        return change
    }

    override fun addChangeListener(listener: FlagChangeListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeChangeListener(listener: FlagChangeListener) {
        listeners.remove(listener)
    }
}
