@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.md § Endpoint functions. */
class EndpointFunctionTests {

    @Test
    fun `rejects a non-suspend endpoint`() {
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
                    fun Res.get(): Either<Unit, NoFail>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override fun Res.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("suspend") }, result.errors().toString())
    }

    @Test
    fun `rejects an endpoint with a default body`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.Either.Companion.unit
                import org.kodein.rest.nomi.NoFail
                import org.kodein.rest.nomi.server.ServerEndpointService

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(): Either<Unit, NoFail> = unit()
                }
                @ServerEndpointService
                class ServerImpl : Svc
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("abstract") }, result.errors().toString())
    }

    @Test
    fun `rejects an endpoint with type parameters`() {
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
                    suspend fun <T> Res.get(): Either<Unit, NoFail>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun <T> Res.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("type parameters") }, result.errors().toString())
    }

    @Test
    fun `rejects inline, infix, operator, external and tailrec endpoints`() {
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
                    suspend inline fun Res.get(): Either<Unit, NoFail>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("inline") }, result.errors().toString())
    }

    @Test
    fun `rejects a JvmName or JvmStatic annotated endpoint`() {
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
                    @JvmName("differentName")
                    suspend fun Res.get(): Either<Unit, NoFail>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("JvmName") }, result.errors().toString())
    }

    @Test
    fun `an unannotated method whose name is not get, post, put, patch or delete is an error`() {
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
                    suspend fun Res.fetch(): Either<Unit, NoFail>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun Res.fetch(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("get, post, put, patch or delete") }, result.errors().toString())
    }

    @Test
    fun `Endpoint annotation always wins over a name that looks like a different verb`() {
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
                    suspend fun Res.get(content: String): Either<Unit, NoFail>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun Res.get(content: String): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        assertTrue(result.generated("NomiServerImplRoutes.kt").contains("post"))
    }

    @Test
    fun `rejects an endpoint with no receiver, a nullable receiver, and a receiver with type arguments`() {
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
                    suspend fun get(): Either<Unit, NoFail>
                    suspend fun Res?.getNullable(): Either<Unit, NoFail>
                    suspend fun List<String>.getList(): Either<Unit, NoFail>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun get(): Either<Unit, NoFail> = TODO()
                    override suspend fun Res?.getNullable(): Either<Unit, NoFail> = TODO()
                    override suspend fun List<String>.getList(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("extension function") }, result.errors().toString())
    }

    @Test
    fun `rejects an endpoint whose receiver is not a Endpoint Resource interface`() {
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

                class NotAResource

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun NotAResource.get(): Either<Unit, NoFail>
                }
                @ServerEndpointService
                class ServerImpl : Svc {
                    override suspend fun NotAResource.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("@Endpoint.Resource") }, result.errors().toString())
    }
}
