package co.featbit.client.store

import co.featbit.client.model.FeatureFlag
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
            val existing = items[flag.id]
            val change = when {
                existing == null -> FlagValueChangedEvent(flag.id, null, flag.variation)
                existing.variation != flag.variation ->
                    FlagValueChangedEvent(flag.id, existing.variation, flag.variation)
                else -> null
            }
            items[flag.id] = flag
            change
        }

        if (event != null) {
            listeners.forEach { it.onChange(event) }
        }
    }

    override fun addChangeListener(listener: FlagChangeListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeChangeListener(listener: FlagChangeListener) {
        listeners.remove(listener)
    }
}
