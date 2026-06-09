package co.featbit.client.changetracker

import co.featbit.client.store.FlagChangeListener
import co.featbit.client.store.FlagValueChangedEvent
import kotlinx.coroutines.flow.Flow

/**
 * Tracks feature flag changes. Subscribers can listen to changes for all flags or for a
 * specific flag key, mirroring the .NET `IFlagTracker`. A [flagChanges] [Flow] is also
 * exposed for idiomatic coroutine/Compose consumers.
 */
public interface FlagTracker {
    /** A hot stream of every flag change, suitable for `collect` from a coroutine. */
    public val flagChanges: Flow<FlagValueChangedEvent>

    /** Subscribes [listener] to changes of any flag. */
    public fun subscribe(listener: FlagChangeListener)

    /** Subscribes [listener] to changes of the flag identified by [key]. */
    public fun subscribe(key: String, listener: FlagChangeListener)

    /** Removes a global [listener]. */
    public fun unsubscribe(listener: FlagChangeListener)

    /** Removes a [listener] registered for [key]. */
    public fun unsubscribe(key: String, listener: FlagChangeListener)
}
