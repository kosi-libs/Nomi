@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SPECS.md § Service declarations, exercised through the server processor - see
 * `processor/README.md`'s "Testing strategy": these rules live in `nomi-processor-common` but have
 * no compiler test harness of their own. The server processor only ever reads a `@Endpoint.Service`
 * interface that some `@ServerEndpointService` class actually implements (SPECS.md § General:
 * "each processor verifies every declaration it reads"), so every fixture below wires one in.
 */
class ServiceDeclarationTests {

    @Test
    fun `rejects a service that is a class`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Service
                open class NotAnInterface : EndpointService

                @ServerEndpointService
                class ServerImpl : NotAnInterface()
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("interface") }, result.errors().toString())
    }

    @Test
    fun `rejects a sealed service interface and a fun service interface`() {
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
                sealed interface SealedService : EndpointService
                @Endpoint.Service
                fun interface FunService : EndpointService {
                    suspend fun Res.get(): Either<Unit, NoFail>
                }

                @ServerEndpointService
                class ServerFunImpl : FunService {
                    override suspend fun Res.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("fun interface") }, result.errors().toString())
    }

    @Test
    fun `rejects a service nested inside a resource`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Resource
                interface Res {
                    @Endpoint.Service
                    interface NestedService : EndpointService
                }

                @ServerEndpointService
                class ServerImpl : Res.NestedService
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("resource") }, result.errors().toString())
    }

    @Test
    fun `rejects a service that does not extend EndpointService`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Service
                interface NotAService

                @ServerEndpointService
                class ServerImpl : NotAService
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("EndpointService") }, result.errors().toString())
    }

    @Test
    fun `accepts a plain member-less supertype, rejects one declaring members`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.server.ServerEndpointService

                interface PlainMarker
                interface PlainWithMembers { suspend fun x() }

                @Endpoint.Service
                interface OkService : EndpointService, PlainMarker
                @Endpoint.Service
                interface BadService : EndpointService, PlainWithMembers

                @ServerEndpointService
                class ServerOk : OkService
                @ServerEndpointService
                class ServerBad : BadService {
                    override suspend fun x() = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("PlainWithMembers") }, result.errors().toString())
        assertTrue(result.errors().none { it.contains("PlainMarker") }, result.errors().toString())
    }

    @Test
    fun `rejects a service also annotated Endpoint Resource`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Resource
                @Endpoint.Service
                interface Both : EndpointService

                @ServerEndpointService
                class ServerImpl : Both
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("also be annotated") }, result.errors().toString())
    }

    @Test
    fun `rejects a service declaring a property, a nested type or a typealias`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Service
                interface BadService : EndpointService {
                    val prop: Int
                    class Nested
                    typealias Alias = Int
                }

                @ServerEndpointService
                class ServerImpl : BadService {
                    override val prop: Int = 0
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("prop") }, errors.toString())
        assertTrue(errors.any { it.contains("Nested") }, errors.toString())
        assertTrue(errors.any { it.contains("Alias") }, errors.toString())
    }

    @Test
    fun `rejects a service overriding an inherited endpoint`() {
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
                interface Base : EndpointService {
                    suspend fun Res.get(): Either<Unit, NoFail>
                }
                @Endpoint.Service
                interface Derived : Base {
                    override suspend fun Res.get(): Either<Unit, NoFail>
                }

                @ServerEndpointService
                class ServerImpl : Derived {
                    override suspend fun Res.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("override") }, result.errors().toString())
    }

    @Test
    fun `a service declaring zero endpoints is legal`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Service
                interface EmptyBase : EndpointService

                @ServerEndpointService
                class ServerImpl : EmptyBase
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects two endpoints of one service resolving to the same path and method`() {
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

                @Endpoint.Resource("res")
                interface ResA
                @Endpoint.Resource("res")
                interface ResB

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun ResA.get(): Either<Unit, NoFail>
                    suspend fun ResB.get(): Either<Unit, NoFail>
                }

                @ServerEndpointService
                class ServerSvc : Svc {
                    override suspend fun ResA.get(): Either<Unit, NoFail> = TODO()
                    override suspend fun ResB.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("both resolve to") }, result.errors().toString())
    }
}
