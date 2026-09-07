package org.kodein.rest.nomi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class TestError(val reason: String) : Error

class EitherTests {

    // --- construction & isValue/isError -------------------------------------------------------

    @Test
    fun `value is a value and not an error`() {
        val e: Either<Int, TestError> = Either.value(5)
        assertTrue(e.isValue(NType()))
        assertTrue(e.isValue())
        assertFalse(e.isError(NType()))
        assertFalse(e.isError())
    }

    @Test
    fun `error is an error and not a value`() {
        val e: Either<Int, TestError> = Either.error(TestError("bad"))
        assertFalse(e.isValue(NType()))
        assertFalse(e.isValue())
        assertTrue(e.isError(NType()))
        assertTrue(e.isError())
    }

    @Test
    fun `unit is a Unit value`() {
        val e: Either<Unit, TestError> = Either.unit()
        assertTrue(e.isValue(NType()))
        assertTrue(e.isValue())
        assertEquals(Unit, e.value(NType()))
        assertEquals(Unit, e.value())
    }

    @Test
    fun `a null value is still a value - never an error`() {
        // This is load-bearing: it is how a 204 No Content becomes a null value on the client.
        val e: Either<String?, TestError> = Either.value(null)
        assertTrue(e.isValue(NType()))
        assertTrue(e.isValue())
        assertFalse(e.isError(NType()))
        assertFalse(e.isError())
        assertNull(e.valueOrNull(NType()))
        assertNull(e.valueOrNull())
        assertNull(e.errorOrNull(NType()))
        assertNull(e.errorOrNull())
    }

    // --- valueOrNull / errorOrNull -------------------------------------------------------------

    @Test
    fun `valueOrNull and errorOrNull on a value`() {
        val e: Either<String?, TestError> = Either.value("hello")
        assertEquals("hello", e.valueOrNull(NType()))
        assertEquals("hello", e.valueOrNull())
        assertNull(e.errorOrNull(NType()))
        assertNull(e.errorOrNull())
    }

    @Test
    fun `valueOrNull and errorOrNull on an error`() {
        val e: Either<String?, TestError> = Either.error(TestError("bad"))
        assertNull(e.valueOrNull(NType()))
        assertNull(e.valueOrNull())
        assertEquals(TestError("bad"), e.errorOrNull(NType()))
        assertEquals(TestError("bad"), e.errorOrNull())
    }

    // --- value() / error() throwing on mismatch -------------------------------------------------

    @Test
    fun `value throws on an error`() {
        val e: Either<Int, TestError> = Either.error(TestError("bad"))
        val ex1 = assertFailsWith<IllegalStateException> { e.value(NType()) }
        assertTrue(ex1.message!!.startsWith("Expected"))
        val ex2 = assertFailsWith<IllegalStateException> { e.value() }
        assertTrue(ex2.message!!.startsWith("Expected"))
    }

    @Test
    fun `error throws on a value`() {
        val e: Either<Int, TestError> = Either.value(5)
        val ex1 = assertFailsWith<IllegalStateException> { e.error(NType()) }
        assertTrue(ex1.message!!.endsWith("but was 5"))
        val ex2 = assertFailsWith<IllegalStateException> { e.error() }
        assertTrue(ex2.message!!.endsWith("but was 5"))
    }

    // --- onValue / onError ----------------------------------------------------------------------

    @Test
    fun `onValue runs its block once on a value and returns the receiver`() {
        val e: Either<Int, TestError> = Either.value(5)
        var seen: Int? = null
        val returned = e.onValue(NType()) { seen = it }
        assertEquals(5, seen)
        assertEquals(e, returned)

        seen = null
        val returnedReified = e.onValue { seen = it }
        assertEquals(5, seen)
        assertEquals(e, returnedReified)
    }

    @Test
    fun `onValue never runs its block on an error`() {
        val e: Either<Int, TestError> = Either.error(TestError("bad"))
        var called = false
        e.onValue(NType()) { called = true }
        assertFalse(called)
        e.onValue { called = true }
        assertFalse(called)
    }

    @Test
    fun `onError runs its block once on an error and returns the receiver`() {
        val e: Either<Int, TestError> = Either.error(TestError("bad"))
        var seen: TestError? = null
        val returned = e.onError(NType()) { seen = it }
        assertEquals(TestError("bad"), seen)
        assertEquals(e, returned)

        seen = null
        val returnedReified = e.onError { seen = it }
        assertEquals(TestError("bad"), seen)
        assertEquals(e, returnedReified)
    }

    @Test
    fun `onError never runs its block on a value`() {
        val e: Either<Int, TestError> = Either.value(5)
        var called = false
        e.onError(NType()) { called = true }
        assertFalse(called)
        e.onError { called = true }
        assertFalse(called)
    }

    // --- on(value, error) -------------------------------------------------------------------------

    @Test
    fun `on dispatches to the value branch`() {
        val e: Either<Int, TestError> = Either.value(5)
        assertEquals("V:5", e.on(NType(), NType(), value = { "V:$it" }, error = { "E:${it.reason}" }))
        assertEquals("V:5", e.on(value = { "V:$it" }, error = { "E:${it.reason}" }))
    }

    @Test
    fun `on dispatches to the error branch`() {
        val e: Either<Int, TestError> = Either.error(TestError("bad"))
        assertEquals("E:bad", e.on(NType(), NType(), value = { "V:$it" }, error = { "E:${it.reason}" }))
        assertEquals("E:bad", e.on(value = { "V:$it" }, error = { "E:${it.reason}" }))
    }

    @Test
    fun `on throws when content matches neither the declared value nor error type`() {
        // Reachable only through an unchecked cast - the runtime content (an Int) no longer
        // matches the statically declared V (String) nor E (TestError).
        @Suppress("UNCHECKED_CAST")
        val weird = Either.value<Int, TestError>(42) as Either<String, TestError>

        val ex1 = assertFailsWith<IllegalStateException> {
            weird.on(NType(), NType(), value = { it }, error = { it.reason })
        }
        assertTrue(ex1.message!!.startsWith("Expected"))

        val ex2 = assertFailsWith<IllegalStateException> {
            weird.on(value = { it }, error = { it.reason })
        }
        assertTrue(ex2.message!!.startsWith("Expected"))
    }

    // --- valueOr -------------------------------------------------------------------------------

    @Test
    fun `valueOr returns the value without calling the block`() {
        val e: Either<Int, TestError> = Either.value(5)
        assertEquals(5, e.valueOr(NType(), NType()) { error("should not be called") })
        assertEquals(5, e.valueOr { error("should not be called") })
    }

    @Test
    fun `valueOr computes from the error`() {
        val e: Either<Int, TestError> = Either.error(TestError("nope"))
        assertEquals(4, e.valueOr(NType(), NType()) { it.reason.length })
        assertEquals(4, e.valueOr { it.reason.length })
    }

    // --- mapValue / mapError -------------------------------------------------------------------

    @Test
    fun `mapValue transforms a value`() {
        val e: Either<Int, TestError> = Either.value(5)
        assertEquals(10, e.mapValue(NType()) { it * 2 }.value(NType()))
        assertEquals(10, e.mapValue { it * 2 }.value())
    }

    @Test
    fun `mapValue leaves an error untouched`() {
        val e: Either<Int, TestError> = Either.error(TestError("nope"))
        val mapped1 = e.mapValue(NType()) { it.toString() }
        assertEquals(TestError("nope"), mapped1.errorOrNull(NType()))
        val mapped2 = e.mapValue { it.toString() }
        assertEquals(TestError("nope"), mapped2.errorOrNull())
    }

    @Test
    fun `mapError transforms an error`() {
        val e: Either<Int, TestError> = Either.error(TestError("nope"))
        val mapped1 = e.mapError(NType()) { TestError(it.reason.uppercase()) }
        assertEquals(TestError("NOPE"), mapped1.errorOrNull(NType()))
        val mapped2 = e.mapError { TestError(it.reason.uppercase()) }
        assertEquals(TestError("NOPE"), mapped2.errorOrNull())
    }

    @Test
    fun `mapError leaves a value untouched`() {
        val e: Either<Int, TestError> = Either.value(5)
        val mapped1 = e.mapError(NType()) { TestError(it.reason.uppercase()) }
        assertEquals(5, mapped1.value(NType()))
        val mapped2 = e.mapError { TestError(it.reason.uppercase()) }
        assertEquals(5, mapped2.value())
    }

    // NoFail.isImpossible() is deliberately not tested here: NoFail has a private constructor and
    // is never instantiated at runtime, so there is no safe way to obtain one - not even an
    // unchecked generic cast, since the JVM still checkcasts it at the extension function's
    // receiver boundary. That uninstantiability is the whole point of the type.

    // --- NType -----------------------------------------------------------------------------------

    @Test
    fun `NType exposes the reified KClass`() {
        assertEquals(TestError::class, NType<TestError>().kClass)
    }
}
