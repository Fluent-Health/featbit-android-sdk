package co.featbit.client.domain

import co.featbit.client.model.FeatureFlag
import co.featbit.client.store.FlagChangeListener

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

    /**
     * Bulk variant of [upsert]. The default implementation iterates [upsert], preserving the
     * legacy "write-then-notify per flag" ordering. Adapters MAY override to batch writes
     * under a single lock; in that case change events fire AFTER all writes complete, which
     * gives listeners a consistent post-batch snapshot rather than interleaved partial state.
     * Both orderings are valid — callers must not rely on which one they see.
     *
     * Change events are still raised one-per-flag (with original key/old/new values),
     * irrespective of batching.
     */
    public fun upsertAll(flags: Collection<FeatureFlag>) {
        for (flag in flags) upsert(flag)
    }

    /** Registers a listener invoked whenever a flag value changes. */
    public fun addChangeListener(listener: FlagChangeListener)

    /** Removes a previously registered change listener. */
    public fun removeChangeListener(listener: FlagChangeListener)
}
