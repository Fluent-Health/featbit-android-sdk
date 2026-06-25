package co.featbit.client.domain

import co.featbit.client.model.FeatureFlag
import co.featbit.client.store.DefaultMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [EvalResult] contract and the [Evaluator] mapping from store lookup to result.
 *
 * The sealed-interface migration replaced the previous `(reason, flag?, isValid)` triple. The
 * wire-compat strings — `"flag not found"` and whatever `flag.matchReason` returns — are part
 * of the public surface (they end up in `EvalDetail.reason`), so we lock them in here.
 */
class EvalResultTest {

    @Test
    fun `NotFound reason is the wire-compat string`() {
        assertEquals("flag not found", EvalResult.NotFound.reason)
    }

    @Test
    fun `Found reason derives from the flag's matchReason`() {
        val flag = FeatureFlag(id = "k", variation = "true", matchReason = "fallthrough")
        val found = EvalResult.Found(flag)

        assertEquals("fallthrough", found.reason)
        assertEquals("true", found.value)
        assertSame(flag, found.flag)
    }

    @Test
    fun `evaluator returns NotFound for an unknown key`() {
        val evaluator = Evaluator(DefaultMemoryStore())
        assertTrue("unknown key", evaluator.evaluate("nope") is EvalResult.NotFound)
    }

    @Test
    fun `evaluator returns Found wrapping the stored flag`() {
        val store = DefaultMemoryStore()
        val flag = FeatureFlag(id = "k", variation = "v", matchReason = "rule_match")
        store.upsert(flag)

        val result = evaluator(store).evaluate("k")
        assertTrue("found", result is EvalResult.Found)
        result as EvalResult.Found
        assertSame(flag, result.flag)
        assertEquals("rule_match", result.reason)
        assertEquals("v", result.value)
    }

    private fun evaluator(store: DefaultMemoryStore) = Evaluator(store)
}
