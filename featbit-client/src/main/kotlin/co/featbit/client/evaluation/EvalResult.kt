package co.featbit.client.evaluation

import co.featbit.client.model.FeatureFlag

/**
 * Internal result of looking a flag up in the store, prior to type conversion.
 */
internal class EvalResult private constructor(
    val isValid: Boolean,
    val reason: String,
    val value: String,
) {
    companion object {
        /** The caller provided a key that did not match any known flag. */
        val FlagNotFound: EvalResult = EvalResult(false, "flag not found", "")

        fun of(flag: FeatureFlag): EvalResult = EvalResult(true, flag.matchReason, flag.variation)
    }
}
