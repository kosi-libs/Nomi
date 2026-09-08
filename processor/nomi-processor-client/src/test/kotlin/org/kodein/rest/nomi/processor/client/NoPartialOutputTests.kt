@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.client

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.md § Verifications: "generates nothing for that round if any declaration it would need
 * is invalid — never a partial file." The gate is round-global, matching
 * `nomi-processor-shared`/`-server`'s own precedent: a bad registration on one object still blocks
 * a valid registration on a different object from generating this round, even though each is
 * validated and reported independently. */
class NoPartialOutputTests {

    @Test
    fun `generates nothing when any object-level factory declaration in the round is invalid`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.NoFail
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(): Either<Unit, NoFail>
                }

                @ClientEndpointServiceFactory(Svc::class, "good")
                object Good

                @ClientEndpointServiceFactory(Svc::class, "bad")
                class Bad
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.generatedFiles().none { it.name.endsWith("Client.kt") }, result.generatedFiles().toString())
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
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @Endpoint.Resource
                interface Res

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(): Either<Unit, NoFail>
                }

                interface NotAService : EndpointService

                @ClientEndpointServiceFactory(Svc::class, "1notvalid")
                object FirstFactory
                @ClientEndpointServiceFactory(NotAService::class, "ok")
                object SecondFactory
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("non-blank and match") }, errors.toString())
        assertTrue(errors.any { it.contains("NotAService") }, errors.toString())
        assertTrue(result.generatedFiles().none { it.name.endsWith("Client.kt") }, result.generatedFiles().toString())
    }
}
