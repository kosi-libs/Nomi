@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.md § Server implementations (`@ServerEndpointService`) - the server-only rules. */
class ServerEndpointServiceTests {

    @Test
    fun `rejects an interface, an abstract class, a sealed class, an annotation class and an enum class`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Service
                interface Svc : EndpointService

                @ServerEndpointService
                interface ServerInterface : Svc
                @ServerEndpointService
                abstract class ServerAbstract : Svc
                @ServerEndpointService
                sealed class ServerSealed : Svc
                @ServerEndpointService
                annotation class ServerAnnotation
                @ServerEndpointService
                enum class ServerEnum
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("ServerInterface") && it.contains("concrete") }, errors.toString())
        assertTrue(errors.any { it.contains("ServerAbstract") && it.contains("abstract") }, errors.toString())
        assertTrue(errors.any { it.contains("ServerSealed") && it.contains("sealed") }, errors.toString())
        assertTrue(errors.any { it.contains("ServerAnnotation") && it.contains("concrete") }, errors.toString())
        assertTrue(errors.any { it.contains("ServerEnum") && it.contains("concrete") }, errors.toString())
    }

    @Test
    fun `accepts a concrete class and an object`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Service
                interface Svc : EndpointService

                @ServerEndpointService
                class ServerClass : Svc
                @ServerEndpointService
                object ServerObject : Svc
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects a class nested inside a resource`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Service
                interface Svc : EndpointService

                @Endpoint.Resource
                interface Res {
                    @ServerEndpointService
                    class ServerImpl : Svc
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("resource") }, result.errors().toString())
    }

    @Test
    fun `rejects a class implementing no Endpoint Service interface`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.server.ServerEndpointService

                @ServerEndpointService
                class ServerImpl
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("must implement at least one") }, result.errors().toString())
    }

    @Test
    fun `accepts a class implementing a service transitively through a plain marker interface`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Service
                interface Svc : EndpointService

                interface Intermediate : Svc

                @ServerEndpointService
                class ServerImpl : Intermediate
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects two endpoints of different services colliding on path and method`() {
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
                interface SvcA : EndpointService {
                    suspend fun ResA.get(): Either<Unit, NoFail>
                }
                @Endpoint.Service
                interface SvcB : EndpointService {
                    suspend fun ResB.get(): Either<Unit, NoFail>
                }

                @ServerEndpointService
                class ServerImpl : SvcA, SvcB {
                    override suspend fun ResA.get(): Either<Unit, NoFail> = TODO()
                    override suspend fun ResB.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("both resolve to") }, result.errors().toString())
    }

    @Test
    fun `combines endpoints of several services implemented together into one route function`() {
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

                @Endpoint.Resource("a")
                interface ResA
                @Endpoint.Resource("b")
                interface ResB

                @Endpoint.Service
                interface SvcA : EndpointService {
                    suspend fun ResA.get(): Either<Unit, NoFail>
                }
                @Endpoint.Service
                interface SvcB : EndpointService {
                    suspend fun ResB.get(): Either<Unit, NoFail>
                }

                @ServerEndpointService
                class ServerImpl : SvcA, SvcB {
                    override suspend fun ResA.get(): Either<Unit, NoFail> = TODO()
                    override suspend fun ResB.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiServerImplRoutes.kt")
        assertTrue(generated.contains("\"/a\""), generated)
        assertTrue(generated.contains("\"/b\""), generated)
    }
}
