@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.client

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.adoc § Generated-name collisions - the client-related, round-scoped bullets handled by
 * [ClientRoundChecks]. */
class NameCollisionTests {

    @Test
    fun `rejects two differently-nested objects with the same simple name registering the same service`() {
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

                @ClientEndpointServiceFactory(Svc::class, "top")
                object APIServices

                class Namespace {
                    @ClientEndpointServiceFactory(Svc::class, "nested")
                    object APIServices
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("both generate the client implementation name") }, result.errors().toString())
    }

    @Test
    fun `accepts two objects with different simple names registering the same service`() {
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
                object FirstFactory
                @ClientEndpointServiceFactory(Svc::class, "two")
                object SecondFactory
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }
}
