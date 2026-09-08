@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.shared

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.adoc § General: "Stray annotations are errors." Full rule coverage lives in
 * `nomi-processor-server`'s tests (it reads both resources and services); this is a smoke test
 * confirming the shared processor's own invocation of the sweep actually runs - the only one of
 * the four rules meaningfully reachable in a resources-only compilation. */
class StrayAnnotationTests {

    @Test
    fun `rejects Endpoint UseStringer on a class that is neither a resource, a service nor an annotation class`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.Either.Companion.value

                object StrStringer : Stringer<Int> {
                    override fun encode(value: Int) = value.toString()
                    override fun decode(encoded: String) = value<Int, Stringer.Failure>(encoded.toInt())
                }

                @Endpoint.UseStringer(Int::class, StrStringer::class)
                class UnrelatedClass
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("@Endpoint.UseStringer on") && it.contains("UnrelatedClass") }, result.errors().toString())
    }
}
