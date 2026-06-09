package co.featbit.client.store

/**
 * An event raised when the value of a feature flag changes.
 */
public data class FlagValueChangedEvent(
    /** The key of the feature flag whose value changed. */
    val key: String,
    /** The previous value of the flag, or `null` if the flag was not previously known. */
    val oldValue: String?,
    /** The new value of the flag. */
    val newValue: String,
)

/**
 * A listener notified when a feature flag value changes. Used both internally by the store
 * and as the public subscriber type of [co.featbit.client.changetracker.FlagTracker].
 */
public fun interface FlagChangeListener {
    public fun onChange(event: FlagValueChangedEvent)
}
