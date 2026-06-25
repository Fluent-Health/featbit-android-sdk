package co.featbit.client.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValueConvertersTest {

    @Test
    fun `bool parses case-insensitively and trims`() {
        assertEquals(true, ValueConverters.bool("true"))
        assertEquals(true, ValueConverters.bool(" TRUE "))
        assertEquals(false, ValueConverters.bool("False"))
        assertNull(ValueConverters.bool("yes"))
    }

    /**
     * Contract: bool conversion uses `equals(ignoreCase = true)` to avoid the
     * `lowercase()`-then-compare allocation pattern. Pin every mixed-case permutation we'd
     * realistically see from a flag server: legitimate cases must parse, near-matches must
     * reject (we don't want "Truee" to slide).
     *
     * Mutation that would fail this:
     *   * Reverting to `value.trim().lowercase() == "true"` — passes for legit strings (no
     *     visible change) but masks an allocation regression. Not directly assertable here.
     *   * Replacing `equals("true", ignoreCase = true)` with `==`/`equals("true")` — capital
     *     variants would fall through to null.
     *   * Dropping the trim — leading/trailing whitespace strings would fail.
     *   * Adding new fuzzy matches like "yes"/"1" → would break the `assertNull` rejections.
     */
    @Test
    fun `bool conversion covers mixed case and near-match permutations`() {
        // Mixed-case truthy: every variant must resolve. Note we don't cover lowercase
        // "true" / "false" here — `bool parses case-insensitively and trims` already does.
        listOf("TRUE", "True", "tRuE", "  True  ", "TRUE\t").forEach { input ->
            assertEquals("'$input' must parse as true", true, ValueConverters.bool(input))
        }
        listOf("FALSE", "False", "fAlSe", "  false  ", "false\n").forEach { input ->
            assertEquals("'$input' must parse as false", false, ValueConverters.bool(input))
        }

        // Near-matches must reject — don't expand fuzzy parsing without a deliberate decision.
        listOf("trues", "True ish", "yes", "1", "0", "y", "n", "", "  ", "null").forEach { input ->
            assertNull("'$input' must reject (not a bool)", ValueConverters.bool(input))
        }
    }

    @Test
    fun `int parses valid and rejects invalid`() {
        assertEquals(42, ValueConverters.int(" 42 "))
        assertNull(ValueConverters.int("42.5"))
        assertNull(ValueConverters.int("abc"))
    }

    @Test
    fun `float and double parse`() {
        assertEquals(1.5f, ValueConverters.float("1.5"))
        assertEquals(2.25, ValueConverters.double(" 2.25 "))
        assertNull(ValueConverters.double("not-a-number"))
    }

    @Test
    fun `string passes through unchanged`() {
        assertEquals("anything", ValueConverters.string("anything"))
    }
}
