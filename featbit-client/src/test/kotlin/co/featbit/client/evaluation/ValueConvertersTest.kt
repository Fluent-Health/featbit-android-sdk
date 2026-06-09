package co.featbit.client.evaluation

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
