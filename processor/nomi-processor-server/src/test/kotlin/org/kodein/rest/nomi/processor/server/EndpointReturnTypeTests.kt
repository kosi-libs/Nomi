@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.md § Endpoint return types. */
class EndpointReturnTypeTests {

    @Test
    fun `rejects a return type that is not Either`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(): String
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun Res.get(): String = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("Either") }, result.errors().toString())
    }

    @Test
    fun `accepts a parameterized typealias expanding to Either`() {
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

                typealias Ok<V> = Either<V, NoFail>

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(): Ok<String>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Ok<String> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects a nullable Either return type`() {
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
                    suspend fun Res.get(): Either<Unit, NoFail>?
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Unit, NoFail>? = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("nullable") }, result.errors().toString())
    }

    @Test
    fun `rejects Error itself and Nothing as the failure type`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.Error
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(): Either<Unit, Error>
                    suspend fun Res.delete(): Either<Unit, Nothing>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Unit, Error> = TODO()
                    override suspend fun Res.delete(): Either<Unit, Nothing> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("Error itself") }, errors.toString())
        assertTrue(errors.any { it.contains("must not be Nothing") }, errors.toString())
    }

    @Test
    fun `rejects a failure type not implementing Error`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(): Either<Unit, String>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Unit, String> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("must implement Error") }, result.errors().toString())
    }

    @Test
    fun `rejects Nothing, Any, Any-nullable and Unit-nullable as the success type`() {
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
                    suspend fun Res.get(): Either<Nothing, NoFail>
                    suspend fun Res.delete(): Either<Any, NoFail>
                    @Endpoint(Endpoint.Method.Post)
                    suspend fun Res.create(body: String): Either<Any?, NoFail>
                    @Endpoint(Endpoint.Method.Put)
                    suspend fun Res.update(body: String): Either<Unit?, NoFail>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Nothing, NoFail> = TODO()
                    override suspend fun Res.delete(): Either<Any, NoFail> = TODO()
                    override suspend fun Res.create(body: String): Either<Any?, NoFail> = TODO()
                    override suspend fun Res.update(body: String): Either<Unit?, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("must not be Nothing") }, errors.toString())
        assertTrue(errors.any { it.contains("must not be Any") && !it.contains("Any?") }, errors.toString())
        assertTrue(errors.any { it.contains("Any?") }, errors.toString())
        assertTrue(errors.any { it.contains("Unit?") }, errors.toString())
    }

    @Test
    fun `rejects NoFail as the success type`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.NoFail
                import org.kodein.rest.nomi.Error
                import org.kodein.rest.nomi.server.ServerEndpointService

                class SomeFailure : Error

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(): Either<NoFail, SomeFailure>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<NoFail, SomeFailure> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("NoFail") }, result.errors().toString())
    }

    @Test
    fun `rejects overlapping success and failure types`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.Error
                import org.kodein.rest.nomi.server.ServerEndpointService

                open class Shared : Error
                class SharedFailure : Shared()

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(): Either<Shared, SharedFailure>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Shared, SharedFailure> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("disjoint") }, result.errors().toString())
    }

    @Test
    fun `accepts Unit and a nullable success type`() {
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
                    suspend fun Res.get(): Either<Unit, NoFail>
                    suspend fun Res.delete(): Either<String?, NoFail>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Unit, NoFail> = TODO()
                    override suspend fun Res.delete(): Either<String?, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }
}
