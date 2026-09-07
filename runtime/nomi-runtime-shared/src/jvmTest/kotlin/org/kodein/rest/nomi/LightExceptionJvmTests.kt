package org.kodein.rest.nomi

import kotlin.test.Test
import kotlin.test.assertTrue

private class TestLightExceptionJvm(message: String? = null) : LightException(message)

class LightExceptionJvmTests {

    @Test
    fun `the JVM actual has no stack trace`() {
        // This is the entire reason LightException exists: a network/protocol exception that can
        // be thrown on every failed call must not pay for capturing a stack trace.
        val e = TestLightExceptionJvm("boom")
        assertTrue(e.stackTrace.isEmpty())
    }
}
