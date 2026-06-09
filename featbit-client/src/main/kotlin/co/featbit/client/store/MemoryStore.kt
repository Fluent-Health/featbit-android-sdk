package co.featbit.client.store

import co.featbit.client.model.FeatureFlag

/**
 * A thread-safe in-memory store holding the feature flag data received by the SDK.
 */
public interface MemoryStore {
    /** Returns the flag with the given [id], or `null` if unknown. */
    public fun get(id: String): FeatureFlag?

    /** Returns a snapshot of all flags currently in the store. */
    public fun getAll(): Collection<FeatureFlag>

    /** Inserts or updates [flag], raising a change event if its value changed. */
    public fun upsert(flag: FeatureFlag)

    /** Registers a listener invoked whenever a flag value changes. */
    public fun addChangeListener(listener: FlagChangeListener)

    /** Removes a previously registered change listener. */
    public fun removeChangeListener(listener: FlagChangeListener)
}
