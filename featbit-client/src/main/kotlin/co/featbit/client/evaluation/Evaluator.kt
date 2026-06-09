package co.featbit.client.evaluation

import co.featbit.client.model.FeatureFlag
import co.featbit.client.store.MemoryStore

/**
 * Resolves a feature flag from the store, returning the lookup [EvalResult] alongside the
 * matched [FeatureFlag] (or `null` when the flag is unknown).
 */
internal class Evaluator(private val store: MemoryStore) {
    fun evaluate(key: String): Pair<EvalResult, FeatureFlag?> {
        val flag = store.get(key) ?: return EvalResult.FlagNotFound to null
        return EvalResult.of(flag) to flag
    }
}
