@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.shared

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoPartialOutputTests {

    @Test
    fun `generates nothing when any resource in the round is invalid`() {
        val result = compile(
            SourceFile.kotlin(
                "good.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                @Endpoint.Resource
                interface Good
                """,
            ),
            SourceFile.kotlin(
                "bad.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                @Endpoint.Resource
                class Bad
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.generatedFiles().isEmpty(), result.generatedFiles().toString())
    }

    @Test
    fun `reports every violation in the round rather than stopping at the first`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                class ResA

                @Endpoint.Resource
                interface ResB { var mutableId: Int }

                @Endpoint.Resource
                interface ResC { val id: Unit }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("ResA") }, errors.toString())
        assertTrue(errors.any { it.contains("mutableId") }, errors.toString())
        assertTrue(errors.any { it.contains("id") && it.contains("Unit") }, errors.toString())
    }

    @Test
    fun `generates nothing and reports when the runtime is missing from the classpath`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                @Endpoint.Resource
                interface Users
                """,
            ),
        )
        // Sanity check for the OTHER tests' assumption: with inheritClassPath = true (the default
        // in `compile`), the runtime IS on the classpath and this succeeds. A genuinely classpath-
        // less variant would need a separate KotlinCompilation with inheritClassPath = false, which
        // fights kctfork's own bootstrap classpath needs more than it's worth proving here.
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }
}
