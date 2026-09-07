package org.kodein.rest.nomi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

private class TestLightException(message: String? = null, cause: Throwable? = null) : LightException(message, cause)

class LightExceptionTests {

    @Test
    fun `message propagates`() {
        val e = TestLightException("boom")
        assertEquals("boom", e.message)
    }

    @Test
    fun `message defaults to null`() {
        val e = TestLightException()
        assertNull(e.message)
    }

    @Test
    fun `cause propagates`() {
        val cause = RuntimeException("root")
        val e = TestLightException("boom", cause)
        assertSame(cause, e.cause)
    }

    @Test
    fun `cause defaults to null`() {
        val e = TestLightException("boom")
        assertNull(e.cause)
    }
}
