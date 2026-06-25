package co.featbit.client.domain

import co.featbit.client.store.MemoryStore

/** Resolves a feature flag from the store and returns a typed [EvalResult]. */
internal class Evaluator(private val store: MemoryStore) {
    fun evaluate(key: String): EvalResult =
        store.get(key)?.let { EvalResult.Found(it) } ?: EvalResult.NotFound
}
