@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.adoc § Verifications: "each processor verifies every declaration it reads, including ones
 * from a dependency module - it never assumes a dependency was already checked by whichever
 * processor built it." A resource and its service are declared and validated in one module (whose
 * shared processor already generated the resource's `Nomi…` implementation); the
 * `@ServerEndpointService` implementing that service lives in a second module compiled against the
 * first's output - exercising `EndpointChecks`' cross-module "receiver impl resolves from a
 * dependency" path. */
class CrossModuleTests {

    @Test
    fun `generates routes for a service and resource declared in a compiled dependency`() {
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
                "server.kt",
                """
                package moduleB
                import moduleA.Res
                import moduleA.Svc
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.NoFail
                import org.kodein.rest.nomi.server.ServerEndpointService

                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(page: Int?): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, moduleB.exitCode, moduleB.errors().joinToString("\n"))
        val generated = moduleB.generated("NomiServerImplRoutes.kt")
        assertTrue(generated.contains("moduleA.NomiRes"), generated)
        assertTrue(generated.contains("route(\"/res/{id}\")"), generated)
    }

    @Test
    fun `rejects a server implementation whose receiver resource has no generated implementation to resolve`() {
        // The resource lives in moduleA, but its shared processor never ran there (only the
        // server processor did) - so moduleB, implementing moduleA's service, can never resolve
        // the resource's Nomi… implementation.
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
                "server.kt",
                """
                package moduleB
                import moduleA.Res
                import moduleA.Svc
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.NoFail
                import org.kodein.rest.nomi.server.ServerEndpointService

                @ServerEndpointService("svc")
                class ServerImpl : Svc {
                    override suspend fun Res.get(): Either<Unit, NoFail> = TODO()
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, moduleB.exitCode)
        assertTrue(moduleB.errors().any { it.contains("generated implementation") }, moduleB.errors().toString())
    }
}
