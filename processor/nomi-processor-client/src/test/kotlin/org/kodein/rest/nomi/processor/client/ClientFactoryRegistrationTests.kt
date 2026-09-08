@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.client

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.md § Client factories - the per-registration (`serviceClass`, `name`) rules. */
class ClientFactoryRegistrationTests {

    @Test
    fun `rejects a blank name and a name with invalid characters`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @Endpoint.Service
                interface SvcA : EndpointService
                @Endpoint.Service
                interface SvcB : EndpointService

                @ClientEndpointServiceFactory(SvcA::class, "")
                @ClientEndpointServiceFactory(SvcB::class, "not-valid")
                object Factories
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("non-blank and match") }, result.errors().toString())
    }

    @Test
    fun `rejects a name that is a Kotlin hard keyword`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @Endpoint.Service
                interface Svc : EndpointService

                @ClientEndpointServiceFactory(Svc::class, "class")
                object Factories
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("hard keyword") }, result.errors().toString())
    }

    @Test
    fun `accepts a soft keyword as a name`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @Endpoint.Service
                interface Svc : EndpointService

                @ClientEndpointServiceFactory(Svc::class, "get")
                object Factories
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects the same serviceClass registered twice on one object`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @Endpoint.Service
                interface Svc : EndpointService

                @ClientEndpointServiceFactory(Svc::class, "one")
                @ClientEndpointServiceFactory(Svc::class, "two")
                object Factories
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("more than once") }, result.errors().toString())
    }

    @Test
    fun `rejects the same name registered twice on one object`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @Endpoint.Service
                interface SvcA : EndpointService
                @Endpoint.Service
                interface SvcB : EndpointService

                @ClientEndpointServiceFactory(SvcA::class, "svc")
                @ClientEndpointServiceFactory(SvcB::class, "svc")
                object Factories
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("registers name 'svc' more than once") }, result.errors().toString())
    }

    @Test
    fun `rejects a name colliding with an existing member of the object`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @Endpoint.Service
                interface Svc : EndpointService

                @ClientEndpointServiceFactory(Svc::class, "existing")
                object Factories {
                    fun existing() = Unit
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("collides with an existing member") }, result.errors().toString())
    }

    @Test
    fun `accepts several distinct registrations on one object`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @Endpoint.Service
                interface SvcA : EndpointService
                @Endpoint.Service
                interface SvcB : EndpointService

                @ClientEndpointServiceFactory(SvcA::class, "a")
                @ClientEndpointServiceFactory(SvcB::class, "b")
                object Factories
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        assertTrue(result.generatedFiles().any { it.name == "NomiFactoriesSvcAClient.kt" })
        assertTrue(result.generatedFiles().any { it.name == "NomiFactoriesSvcBClient.kt" })
    }
}
