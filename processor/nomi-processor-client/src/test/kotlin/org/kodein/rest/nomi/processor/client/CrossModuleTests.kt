@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.client

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.md § Verifications: "each processor verifies every declaration it reads, including ones
 * from a dependency module - it never assumes a dependency was already checked by whichever
 * processor built it." A resource and its service are declared and validated in one module (whose
 * shared processor already generated the resource's `Nomi…` implementation); the
 * `@ClientEndpointServiceFactory` object naming that service lives in a second module compiled
 * against the first's output - exercising `ServiceAnalyzer`'s cross-module "receiver impl resolves
 * from a dependency" path, unchanged from the server processor's own use of it. */
class CrossModuleTests {

    @Test
    fun `generates a client for a service and resource declared in a compiled dependency`() {
        val moduleA = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package moduleA
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.NoFail

                @Endpoint.Resource
                interface Res { val id: Int }

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Res.get(@Endpoint.Query page: Int? = null): Either<Unit, NoFail>
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, moduleA.exitCode, moduleA.errors().joinToString("\n"))

        val moduleB = compileWith(
            moduleA,
            SourceFile.kotlin(
                "client.kt",
                """
                package moduleB
                import moduleA.Svc
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @ClientEndpointServiceFactory(Svc::class, "svc")
                object Factories
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, moduleB.exitCode, moduleB.errors().joinToString("\n"))
        val generated = moduleB.generated("NomiFactoriesSvcClient.kt")
        assertTrue(generated.contains("moduleA.Res"), generated)
        assertTrue(generated.contains("\"res\""), generated)
        assertTrue(generated.contains(".encode(id)"), generated)
    }

    @Test
    fun `rejects a client factory whose receiver resource has no generated implementation to resolve`() {
        // The resource lives in moduleA, but its shared processor never ran there (only a plain
        // compile did) - so moduleB, naming moduleA's service, can never resolve the resource's
        // Nomi… implementation.
        val moduleA = KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin(
                    "endpoints.kt",
                    """
                    package moduleA
                    import org.kodein.rest.nomi.Endpoint
                    import org.kodein.rest.nomi.EndpointService
                    import org.kodein.rest.nomi.Either
                    import org.kodein.rest.nomi.NoFail

                    @Endpoint.Resource
                    interface Res

                    @Endpoint.Service
                    interface Svc : EndpointService {
                        suspend fun Res.get(): Either<Unit, NoFail>
                    }
                    """,
                ),
            )
            inheritClassPath = true
            jvmTarget = "17"
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, moduleA.exitCode, moduleA.errors().joinToString("\n"))

        val moduleB = compileWith(
            moduleA,
            SourceFile.kotlin(
                "client.kt",
                """
                package moduleB
                import moduleA.Svc
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @ClientEndpointServiceFactory(Svc::class, "svc")
                object Factories
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, moduleB.exitCode)
        assertTrue(moduleB.errors().any { it.contains("generated implementation") }, moduleB.errors().toString())
    }
}
