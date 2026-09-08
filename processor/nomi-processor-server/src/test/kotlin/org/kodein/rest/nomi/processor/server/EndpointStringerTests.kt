@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stringer resolution for endpoint query parameters (SPECS.adoc § Stringers, § Endpoint
 * parameters) - the `Stringer` machinery itself is already proven pure/via KSP in
 * `nomi-processor-common`/`nomi-processor-shared`; this covers only what is specific to endpoints:
 * `String` resolving to the builtin passthrough, and the declaring-service scope rule. */
class EndpointStringerTests {

    @Test
    fun `a String query parameter resolves the builtin StringStringer without any registration`() {
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
                    suspend fun Res.get(@Endpoint.Query name: String): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(name: String): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiServerImplRoutes.kt")
        assertTrue(generated.contains("StringStringer"), generated)
        assertTrue(generated.contains("decodeRequired"), generated)
    }

    @Test
    fun `a custom Stringer registered on the declaring service is used for a query parameter`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.Either.Companion.value
                import org.kodein.rest.nomi.NoFail
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.server.ServerEndpointService
                import java.util.UUID

                object UuidStringer : Stringer<UUID> {
                    override fun encode(value: UUID): String = value.toString()
                    override fun decode(encoded: String): Either<UUID, Stringer.Failure> =
                        value(UUID.fromString(encoded))
                }

                @Endpoint.Resource
                interface Res

                @Endpoint.UseStringer(UUID::class, UuidStringer::class)
                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(@Endpoint.Query id: UUID?): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(id: UUID?): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        assertTrue(result.generated("NomiServerImplRoutes.kt").contains("UuidStringer"))
    }

    @Test
    fun `an endpoint's Stringer scope starts at the interface that declares it, not the leaf service`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.Either.Companion.value
                import org.kodein.rest.nomi.NoFail
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.server.ServerEndpointService
                import java.util.UUID

                object BaseUuidStringer : Stringer<UUID> {
                    override fun encode(value: UUID): String = value.toString()
                    override fun decode(encoded: String): Either<UUID, Stringer.Failure> = value(UUID.fromString(encoded))
                }
                object LeafUuidStringer : Stringer<UUID> {
                    override fun encode(value: UUID): String = value.toString()
                    override fun decode(encoded: String): Either<UUID, Stringer.Failure> = value(UUID.fromString(encoded))
                }

                @Endpoint.Resource
                interface Res

                @Endpoint.UseStringer(UUID::class, BaseUuidStringer::class)
                @Endpoint.Service
                interface Base : EndpointService {
                    suspend fun Res.get(@Endpoint.Query id: UUID?): Either<Unit, NoFail>
                }
                @Endpoint.UseStringer(UUID::class, LeafUuidStringer::class)
                @Endpoint.Service
                interface Derived : Base

                @ServerEndpointService("svc")
                class ServerImpl : Derived {
                    override suspend fun Res.get(id: UUID?): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiServerImplRoutes.kt")
        assertTrue(generated.contains("BaseUuidStringer"), generated)
        assertTrue(!generated.contains("LeafUuidStringer"), generated)
    }

    @Test
    fun `reports no Stringer resolves for a query parameter's type`() {
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
                import java.util.UUID

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(@Endpoint.Query id: UUID?): Either<Unit, NoFail>
                }
                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(id: UUID?): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("no Stringer resolves") }, result.errors().toString())
    }
}
