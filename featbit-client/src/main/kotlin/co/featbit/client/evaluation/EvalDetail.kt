package co.featbit.client.evaluation

/**
 * Describes the result of a feature flag evaluation: the resolved [value] (a flag variation
 * or the supplied default) and a [reason] describing the main factor that influenced it.
 */
public data class EvalDetail<T>(
    /** A human-readable description of why this value was returned. */
    val reason: String,
    /** The evaluated flag value, or the caller's default value. */
    val value: T,
)
