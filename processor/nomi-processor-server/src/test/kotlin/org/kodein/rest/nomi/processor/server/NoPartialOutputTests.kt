@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.md § Verifications: "generates nothing for that round if any declaration it would need
 * is invalid — never a partial file." */
class NoPartialOutputTests {

    @Test
    fun `generates nothing when any ServerEndpointService class in the round is invalid`() {
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
                class Good : Svc {
                    override suspend fun Res.get(): Either<Unit, NoFail> = TODO()
                }

                @ServerEndpointService("svc")
                interface Bad : Svc
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.generatedFiles().none { it.name.endsWith("Routes.kt") }, result.generatedFiles().toString())
    }

    @Test
    fun `reports every violation in the round rather than stopping at the first`() {
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
                interface SvcA : EndpointService {
                    fun Res.get(): Either<Unit, NoFail>
                }
                @Endpoint.Service
                interface SvcB : EndpointService {
                    suspend fun Res.fetch(): Either<Unit, NoFail>
                }

                @ServerEndpointService("svc")
                class ServerA : SvcA {
                    override fun Res.get(): Either<Unit, NoFail> = TODO()
                }
                @ServerEndpointService("svc")
                class ServerB : SvcB {
                    override suspend fun Res.fetch(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("suspend") }, errors.toString())
        assertTrue(errors.any { it.contains("get, post, put, patch or delete") }, errors.toString())
        assertTrue(result.generatedFiles().none { it.name.endsWith("Routes.kt") }, result.generatedFiles().toString())
    }
}
