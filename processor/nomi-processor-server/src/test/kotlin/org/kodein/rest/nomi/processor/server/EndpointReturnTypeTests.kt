@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SPECS.adoc § Endpoint return types. */
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
                @ServerEndpointService("svc")
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
                @ServerEndpointService("svc")
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
                @ServerEndpointService("svc")
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
                @ServerEndpointService("svc")
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
                @ServerEndpointService("svc")
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
                @ServerEndpointService("svc")
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
                @ServerEndpointService("svc")
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
                @ServerEndpointService("svc")
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
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Unit, NoFail> = TODO()
                    override suspend fun Res.delete(): Either<String?, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects a public service whose success type is internal`() {
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

                internal data class Payload(val value: Int)

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(): Either<Payload, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Payload, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("Payload") && it.contains("less visible") }, result.errors().toString())
    }

    @Test
    fun `rejects a public service whose failure type is internal`() {
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

                internal class SomeFailure : Error

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(): Either<Unit, SomeFailure>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Unit, SomeFailure> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("SomeFailure") && it.contains("less visible") }, result.errors().toString())
    }

    /** The source of a one-endpoint service whose success type is [type], server side included. */
    private fun returningSource(type: String) = SourceFile.kotlin(
        "endpoints.kt",
        """
        package test
        import org.kodein.rest.nomi.Endpoint
        import org.kodein.rest.nomi.EndpointBinary
        import org.kodein.rest.nomi.EndpointFile
        import org.kodein.rest.nomi.EndpointService
        import org.kodein.rest.nomi.Either
        import org.kodein.rest.nomi.NoFail
        import org.kodein.rest.nomi.server.ServerEndpointService

        @Endpoint.Resource
        interface Res

        @Endpoint.Service
        interface Svc : EndpointService {
            suspend fun Res.`get`(): Either<$type, NoFail>
        }

        @ServerEndpointService("svc")
        class ServerImpl : Svc {
            override suspend fun Res.`get`(): Either<$type, NoFail> = TODO()
        }
        """,
    )

    @Test
    fun `accepts EndpointBinary as the success type and streams it`() {
        // The response counterpart of an EndpointBinary body. It must not go through respond, which
        // would hand the raw bytes to ContentNegotiation.
        val result = compile(returningSource("EndpointBinary"))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiServerImplRoutes.kt")
        assertTrue(generated.contains("respondEndpointBinary(service)"), generated)
        assertFalse(generated.contains("call.respond(service)"), generated)
    }

    @Test
    fun `rejects EndpointFile as the success type and points at EndpointBinary`() {
        // Same split as on the request side: a file is one part of a multipart body, a whole
        // streamed response is an EndpointBinary.
        val result = compile(returningSource("EndpointFile"))

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.errors().any { it.contains("use EndpointBinary to stream a whole response") },
            result.errors().toString(),
        )
    }

    @Test
    fun `rejects a nullable EndpointBinary as the success type`() {
        // Nothing-to-send already has a spelling - Either<Unit, E>, which answers 204 - so a null
        // binary would be a second way to say it, with no wire form of its own.
        val result = compile(returningSource("EndpointBinary?"))

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.errors().any { it.contains("EndpointBinary cannot be nullable") },
            result.errors().toString(),
        )
    }
}
