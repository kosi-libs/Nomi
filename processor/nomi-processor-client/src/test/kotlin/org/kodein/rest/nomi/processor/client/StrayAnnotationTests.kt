@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.client

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.adoc § General: "Stray annotations are errors." Full rule coverage lives in
 * `nomi-processor-server`'s tests (it reads both resources and services); this is a smoke test
 * confirming the client processor's own invocation of the sweep actually runs. */
class StrayAnnotationTests {

    @Test
    fun `rejects Endpoint Query on a parameter of a non-endpoint function`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                fun notAnEndpoint(@Endpoint.Query stray: Int) = Unit
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("@Endpoint.Query on parameter 'stray'") }, result.errors().toString())
    }
}
