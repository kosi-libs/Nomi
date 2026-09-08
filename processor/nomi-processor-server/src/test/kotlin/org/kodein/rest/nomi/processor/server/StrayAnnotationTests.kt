@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.md § General: "Stray annotations are errors." The server processor reads both resources
 * and services, so it is the natural place to prove all four rules end-to-end. */
class StrayAnnotationTests {

    @Test
    fun `rejects Endpoint on a function outside an Endpoint Service interface`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                interface NotAService {
                    @Endpoint(Endpoint.Method.Post)
                    suspend fun stray()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("@Endpoint on") && it.contains("stray annotation") }, result.errors().toString())
    }

    @Test
    fun `accepts Endpoint on a genuine endpoint`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.NoFail
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    @Endpoint(Endpoint.Method.Post)
                    suspend fun Res.create(content: String): Either<Unit, NoFail>
                }

                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.create(content: String): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

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

    @Test
    fun `rejects Endpoint Query on a parameter of a member function of an unrelated class`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                class Unrelated {
                    fun method(@Endpoint.Query stray: Int) = Unit
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("@Endpoint.Query on parameter 'stray'") }, result.errors().toString())
    }

    @Test
    fun `rejects Endpoint Stringed on a property outside a resource`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.Either.Companion.value

                object StrStringer : Stringer<String> {
                    override fun encode(value: String) = value
                    override fun decode(encoded: String) = value<String, Stringer.Failure>(encoded)
                }

                class NotAResource {
                    @Endpoint.Stringed(StrStringer::class)
                    val strayProperty: String = ""
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("@Endpoint.Stringed on") && it.contains("strayProperty") }, result.errors().toString())
    }

    @Test
    fun `accepts Endpoint Stringed on a genuine resource path parameter`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.Either.Companion.value

                object StrStringer : Stringer<String> {
                    override fun encode(value: String) = value
                    override fun decode(encoded: String) = value<String, Stringer.Failure>(encoded)
                }

                @Endpoint.Resource
                interface Res {
                    @Endpoint.Stringed(StrStringer::class)
                    val id: String
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects Endpoint Stringed on a parameter of a non-endpoint function`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.Either.Companion.value

                object StrStringer : Stringer<String> {
                    override fun encode(value: String) = value
                    override fun decode(encoded: String) = value<String, Stringer.Failure>(encoded)
                }

                fun notAnEndpoint(@Endpoint.Stringed(StrStringer::class) stray: String) = Unit
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("@Endpoint.Stringed on 'stray'") }, result.errors().toString())
    }

    @Test
    fun `accepts Endpoint Stringed on a genuine endpoint query parameter`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.NoFail
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either.Companion.value
                import org.kodein.rest.nomi.server.ServerEndpointService

                object StrStringer : Stringer<String> {
                    override fun encode(value: String) = value
                    override fun decode(encoded: String) = value<String, Stringer.Failure>(encoded)
                }

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(@Endpoint.Stringed(StrStringer::class) @Endpoint.Query q: String): Either<Unit, NoFail>
                }

                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(q: String): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

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

    @Test
    fun `accepts Endpoint UseStringer on a resource, a service and a carrier annotation class`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.Either.Companion.value

                object StrStringer : Stringer<Int> {
                    override fun encode(value: Int) = value.toString()
                    override fun decode(encoded: String) = value<Int, Stringer.Failure>(encoded.toInt())
                }

                @Endpoint.UseStringer(Int::class, StrStringer::class)
                @Endpoint.Resource
                interface Res

                @Endpoint.UseStringer(Int::class, StrStringer::class)
                @Endpoint.Service
                interface Svc : EndpointService

                @Endpoint.UseStringer(Int::class, StrStringer::class)
                @Retention(AnnotationRetention.BINARY)
                @Target(AnnotationTarget.CLASS)
                annotation class CarrierAnnotation
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }
}
