@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.md § Endpoint parameters. */
class EndpointParameterTests {

    @Test
    fun `rejects an unannotated parameter that is not first`() {
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
                    suspend fun Res.create(a: String, b: String): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.create(a: String, b: String): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("unannotated") }, result.errors().toString())
    }

    @Test
    fun `rejects a body parameter on Get or Delete`() {
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
                    suspend fun Res.get(body: String): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(body: String): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("Get") }, result.errors().toString())
    }

    @Test
    fun `rejects a body parameter with a default value or that is nullable`() {
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
                    suspend fun Res.create(body: String = "x"): Either<Unit, NoFail>
                    @Endpoint(Endpoint.Method.Put)
                    suspend fun Res.update(body: String?): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.create(body: String): Either<Unit, NoFail> = TODO()
                    override suspend fun Res.update(body: String?): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("default value") }, errors.toString())
        assertTrue(errors.any { it.contains("nullable") }, errors.toString())
    }

    @Test
    fun `rejects a body parameter carrying Endpoint Stringed`() {
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
                import org.kodein.rest.nomi.Stringer.Failure
                import org.kodein.rest.nomi.Either.Companion.value
                import org.kodein.rest.nomi.server.ServerEndpointService

                object StrStringer : Stringer<String> {
                    override fun encode(value: String) = value
                    override fun decode(encoded: String) = value<String, Failure>(encoded)
                }

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    @Endpoint(Endpoint.Method.Post)
                    suspend fun Res.create(@Endpoint.Stringed(StrStringer::class) body: String): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.create(body: String): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("Stringed") }, result.errors().toString())
    }

    @Test
    fun `rejects a required query parameter with a default value`() {
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
                    suspend fun Res.get(@Endpoint.Query page: Int = 0): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(page: Int): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("default value") }, result.errors().toString())
    }

    @Test
    fun `accepts a nullable query parameter with a non-null default`() {
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
                    suspend fun Res.get(@Endpoint.Query page: Int? = 0): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(page: Int?): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects a query parameter that must not be Unit, Any, a functional type or an array`() {
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
                    suspend fun Res.get(@Endpoint.Query a: Any?): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(a: Any?): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("Any") }, result.errors().toString())
    }

    @Test
    fun `rejects an invalid effective query name and two colliding query names`() {
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
                    suspend fun Res.get(
                        @Endpoint.Query("a b") bad: Int?,
                    ): Either<Unit, NoFail>
                    @Endpoint(Endpoint.Method.Post)
                    suspend fun Res.create(
                        @Endpoint.Query("x") a: Int?,
                        @Endpoint.Query("x") b: Int?,
                    ): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(bad: Int?): Either<Unit, NoFail> = TODO()
                    override suspend fun Res.create(a: Int?, b: Int?): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("non-blank and match") }, errors.toString())
        assertTrue(errors.any { it.contains("same name 'x'") }, errors.toString())
    }

    @Test
    fun `rejects a query parameter colliding with a path parameter of its resource chain`() {
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
                interface Res { val id: Int }

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(@Endpoint.Query id: Int? = null): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(id: Int?): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("collides with a path parameter") }, result.errors().toString())
    }

    @Test
    fun `an endpoint's own query parameter uses its own name by default`() {
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
                    suspend fun Res.get(@Endpoint.Query page: Int? = null): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(page: Int?): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        assertTrue(result.generated("NomiServerImplRoutes.kt").contains("\"page\""))
    }
}
