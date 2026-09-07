package org.kodein.rest.nomi

import org.kodein.rest.nomi.Stringer.Builtins.BooleanStringer
import org.kodein.rest.nomi.Stringer.Builtins.IntStringer
import org.kodein.rest.nomi.Stringer.Builtins.LongStringer
import org.kodein.rest.nomi.Stringer.Builtins.StringStringer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringerTests {

    // --- BooleanStringer -------------------------------------------------------------------------

    @Test
    fun `BooleanStringer encodes true and false`() {
        assertEquals("1", BooleanStringer.encode(true))
        assertEquals("0", BooleanStringer.encode(false))
    }

    @Test
    fun `BooleanStringer decodes both the encoded and the textual forms`() {
        assertEquals(true, BooleanStringer.decode("1").value())
        assertEquals(true, BooleanStringer.decode("true").value())
        assertEquals(false, BooleanStringer.decode("0").value())
        assertEquals(false, BooleanStringer.decode("false").value())
    }

    @Test
    fun `BooleanStringer rejects anything else`() {
        for (input in listOf("", "TRUE", "2", "yes")) {
            val result = BooleanStringer.decode(input)
            assertTrue(result.isError(), "expected '$input' to fail to decode")
            assertEquals("Invalid boolean", result.error().reason)
        }
    }

    // --- IntStringer -------------------------------------------------------------------------------

    @Test
    fun `IntStringer round-trips including the extremes`() {
        for (n in listOf(0, 1, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(n.toString(), IntStringer.encode(n))
            assertEquals(n, IntStringer.decode(n.toString()).value())
        }
    }

    @Test
    fun `IntStringer rejects malformed or out-of-range input`() {
        for (input in listOf("", "1.5", " 1", "2147483648")) {
            val result = IntStringer.decode(input)
            assertTrue(result.isError(), "expected '$input' to fail to decode")
            assertEquals("Invalid integer number", result.error().reason)
        }
    }

    // --- LongStringer -------------------------------------------------------------------------------

    @Test
    fun `LongStringer round-trips including the extremes`() {
        for (n in listOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertEquals(n.toString(), LongStringer.encode(n))
            assertEquals(n, LongStringer.decode(n.toString()).value())
        }
    }

    @Test
    fun `LongStringer accepts values that overflow Int`() {
        assertEquals(2147483648L, LongStringer.decode("2147483648").value())
    }

    @Test
    fun `LongStringer rejects malformed or out-of-range input`() {
        for (input in listOf("", "1.5", " 1", "9223372036854775808")) {
            val result = LongStringer.decode(input)
            assertTrue(result.isError(), "expected '$input' to fail to decode")
            assertEquals("Invalid long integer number", result.error().reason)
        }
    }

    // --- StringStringer ----------------------------------------------------------------------------

    @Test
    fun `StringStringer passes its input straight through unescaped`() {
        for (s in listOf("", "plain", "with space", "a/b?c=d&e#f", "%2F", "λ 漢字 🎉")) {
            assertEquals(s, StringStringer.encode(s))
            assertEquals(s, StringStringer.decode(s).value())
        }
    }

    @Test
    fun `StringStringer never fails to decode`() {
        assertTrue(StringStringer.decode("anything at all").isValue())
    }

    // --- shared cross-check ----------------------------------------------------------------------

    @Test
    fun `Stringer Failure is never mistaken for a decoded value`() {
        val result = IntStringer.decode("nope")
        assertFalse(result.isValue())
        assertTrue(result.isError())
    }
}
