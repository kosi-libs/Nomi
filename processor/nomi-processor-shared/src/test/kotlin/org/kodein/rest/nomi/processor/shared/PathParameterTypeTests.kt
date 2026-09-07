@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.shared

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathParameterTypeTests {

    @Test
    fun `accepts String, Int, Long and Boolean without any registration`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Res {
                    val s: String
                    @Endpoint.Resource
                    interface Nested1 : Res
                }
                @Endpoint.Resource
                interface ResInt { val i: Int }
                @Endpoint.Resource
                interface ResLong { val l: Long }
                @Endpoint.Resource
                interface ResBool { val b: Boolean }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects a nullable path parameter`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Res { val id: Int? }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("id") && it.contains("nullable") }, result.errors().toString())
    }

    @Test
    fun `rejects Unit, Nothing, Any, an array and a functional type`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface ResUnit { val p: Unit }
                @Endpoint.Resource
                interface ResNothing { val p: Nothing }
                @Endpoint.Resource
                interface ResAny { val p: Any }
                @Endpoint.Resource
                interface ResArray { val p: IntArray }
                @Endpoint.Resource
                interface ResFun { val p: () -> Unit }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("p") && (it.contains("Unit") || it.contains("Nothing") || it.contains("Any")) }, errors.toString())
        assertTrue(errors.any { it.contains("array") }, errors.toString())
        assertTrue(errors.any { it.contains("functional") }, errors.toString())
    }

    @Test
    fun `rejects a star projection`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Res { val p: List<*> }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("star") }, result.errors().toString())
    }

    @Test
    fun `accepts a custom Stringer registration for String, overriding the builtin passthrough`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either

                object DummyStringer : Stringer<String> {
                    override fun encode(value: String) = value
                    override fun decode(encoded: String) = Either.Companion.value<String, Stringer.Failure>(encoded)
                }

                @Endpoint.Resource
                interface Res {
                    @Endpoint.Stringed(DummyStringer::class)
                    val name: String
                }

                @Endpoint.Resource
                @Endpoint.UseStringer(String::class, DummyStringer::class)
                interface Res2 {
                    val name: String
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects a parameterized type without Endpoint Stringed, accepts one with it`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either

                object ListStringer : Stringer<List<Int>> {
                    override fun encode(value: List<Int>) = value.joinToString(",")
                    override fun decode(encoded: String) = Either.Companion.value<List<Int>, Stringer.Failure>(encoded.split(",").map { it.toInt() })
                }

                @Endpoint.Resource
                interface ResBad { val ids: List<Int> }

                @Endpoint.Resource
                interface ResGood {
                    @Endpoint.Stringed(ListStringer::class)
                    val ids: List<Int>
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("ids") && it.contains("parameterized") }, errors.toString())
        assertTrue(errors.none { it.contains("ResGood") }, errors.toString())
    }
}
