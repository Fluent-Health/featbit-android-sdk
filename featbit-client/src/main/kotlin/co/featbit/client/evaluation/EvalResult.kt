package co.featbit.client.evaluation

import co.featbit.client.model.FeatureFlag

/**
 * Internal outcome of looking a flag up in the store, prior to type conversion.
 *
 * Modeled as a sealed hierarchy so callers branch on the case rather than threading a
 * `Pair<EvalResult, FeatureFlag?>` plus a stringly-typed `isValid` boolean. The two
 * [Found.reason] / [NotFound.reason] strings match the .NET SDK's wire-compatible reasons.
 */
internal sealed interface EvalResult {
    val reason: String

    /** The flag was found; carry the raw variation string and the source [flag]. */
    data class Found(val flag: FeatureFlag) : EvalResult {
        override val reason: String get() = flag.matchReason
        val value: String get() = flag.variation
    }

    /** The caller provided a key that did not match any known flag. */
    data object NotFound : EvalResult {
        override val reason: String = "flag not found"
    }
}
