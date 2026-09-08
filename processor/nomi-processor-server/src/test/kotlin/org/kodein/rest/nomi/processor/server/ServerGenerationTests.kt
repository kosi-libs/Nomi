@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.md § Server processor - the shape of the generated `fun Route.service(...)`, beyond what
 * [DemoGoldenTests] already pins down exactly. */
class ServerGenerationTests {

    @Test
    fun `a data object resource is referenced bare, a data class resource is constructed with decoded params`() {
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
                interface Bare
                @Endpoint.Resource
                interface WithParam { val id: Int }

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Bare.get(): Either<Unit, NoFail>
                    suspend fun WithParam.get(): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Bare.get(): Either<Unit, NoFail> = TODO()
                    override suspend fun WithParam.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiServerImplRoutes.kt")
        assertTrue(generated.contains("NomiBare\n"), generated) // bare reference, no constructor call
        assertTrue(generated.contains("NomiWithParam(\n"), generated) // constructed with params
        assertTrue(generated.contains("call.parameters[\"id\"]!!"), generated)
    }

    @Test
    fun `a nested resource inherits its parent's path parameter, decoded once per handler`() {
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
                interface Parent {
                    val id: Int
                    @Endpoint.Resource
                    interface Child : Parent
                }

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Parent.get(): Either<Unit, NoFail>
                    suspend fun Parent.Child.get(): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Parent.get(): Either<Unit, NoFail> = TODO()
                    override suspend fun Parent.Child.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiServerImplRoutes.kt")
        // one shared route(){} for "/parent/{id}", with "/child" nested inside it
        assertEquals(1, Regex("route\\(\"/parent/\\{id}\"\\)").findAll(generated).count(), generated)
        assertTrue(generated.contains("route(\"/child\")"), generated)
        // each handler decodes its own copy of the inherited path parameter
        assertEquals(2, Regex("call\\.parameters\\[\"id\"]!!").findAll(generated).count(), generated)
    }

    @Test
    fun `two endpoints of the same resource share one route block, siblings not nested`() {
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
                    suspend fun Res.delete(): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Unit, NoFail> = TODO()
                    override suspend fun Res.delete(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiServerImplRoutes.kt")
        assertEquals(1, Regex("route\\(\"/res\"\\)").findAll(generated).count(), generated)
        assertTrue(generated.contains("`get` {"), generated)
        assertTrue(generated.contains("delete {"), generated)
    }

    @Test
    fun `a body parameter is read with call receive`() {
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
        val generated = result.generated("NomiServerImplRoutes.kt")
        assertTrue(generated.contains("content = call.receive()"), generated)
        assertTrue(generated.contains("post {"), generated)
    }

    @Test
    fun `an internal server implementation generates an internal route function`() {
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
                }
                @ServerEndpointService("svc")
                internal class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiServerImplRoutes.kt")
        assertTrue(generated.contains("internal fun Route.svc"), generated)
    }

    @Test
    fun `the generated route function is named after routeName`() {
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
                }
                @ServerEndpointService("myRoutes")
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiServerImplRoutes.kt")
        assertTrue(generated.contains("fun Route.myRoutes(service: ServerImpl)"), generated)
    }

    @Test
    fun `one file is generated per ServerEndpointService class`() {
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
                }
                @ServerEndpointService("svc")
                class ServerOne : Svc {
                    override suspend fun Res.get(): Either<Unit, NoFail> = TODO()
                }
                @ServerEndpointService("svc")
                class ServerTwo : Svc {
                    override suspend fun Res.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        assertTrue(result.generatedFiles().any { it.name == "NomiServerOneRoutes.kt" })
        assertTrue(result.generatedFiles().any { it.name == "NomiServerTwoRoutes.kt" })
    }
}
