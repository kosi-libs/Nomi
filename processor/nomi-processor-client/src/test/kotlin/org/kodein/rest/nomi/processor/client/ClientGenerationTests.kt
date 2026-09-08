@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.client

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SPECS.adoc § Client processor - generated-code shapes beyond what [DemoGoldenTests] already pins
 * down exactly. */
class ClientGenerationTests {

    @Test
    fun `renders a mixed literal-and-parameter path segment as one concatenated entry`() {
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
                interface Posts {
                    @Endpoint.Resource("p:{postId}")
                    interface Post : Posts { val postId: Int }
                }

                @Endpoint.Service
                interface Svc : EndpointService {
                    suspend fun Posts.Post.get(): Either<Unit, NoFail>
                }

                @ClientEndpointServiceFactory(Svc::class, "svc")
                object Factories
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiFactoriesSvcClient.kt")
        assertTrue(generated.contains("\"p:\" + Stringer.Builtins.IntStringer.encode(postId) + \"\""), generated)
    }

    @Test
    fun `omits the trailing block when there is neither a body nor a query parameter`() {
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

                @ClientEndpointServiceFactory(Svc::class, "svc")
                object Factories
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiFactoriesSvcClient.kt")
        assertTrue(generated.contains("method = Get,"), generated)
        assertFalse(generated.contains(") {"), generated)
    }

    @Test
    fun `an internal object generates an internal extension function but an always-private impl class`() {
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

                @ClientEndpointServiceFactory(Svc::class, "svc")
                internal object Factories
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiFactoriesSvcClient.kt")
        assertTrue(generated.contains("internal fun Factories.svc"), generated)
        assertTrue(generated.contains("private class NomiFactoriesSvcClient"), generated)
    }

    @Test
    fun `an endpoint inherited from a parent service is overridden in the generated client`() {
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
                interface Base : EndpointService {
                    suspend fun Res.get(): Either<Unit, NoFail>
                }
                @Endpoint.Service
                interface Derived : Base

                @ClientEndpointServiceFactory(Derived::class, "svc")
                object Factories
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiFactoriesDerivedClient.kt")
        assertTrue(generated.contains(": Derived,"), generated)
        assertTrue(generated.contains("override suspend fun Res.`get`"), generated)
    }
}
