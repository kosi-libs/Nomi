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

    @Test
    fun `rejects two part types generating the same multipart builder name on one object`() {
        val result = compile(
            SourceFile.kotlin(
                "parts.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.Either
                import org.kodein.rest.nomi.NoFail
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @Endpoint.Resource
                interface Up

                @Endpoint.MultipartFormData
                sealed interface OuterUpload {
                    data class Note(val note: String) : OuterUpload
                }

                class Outer {
                    @Endpoint.MultipartFormData
                    sealed interface Upload {
                        data class Note(val note: String) : Outer.Upload
                    }
                }

                @Endpoint.Service
                interface SvcA : EndpointService {
                    @Endpoint(Endpoint.Method.Post)
                    suspend fun Up.post(parts: EndpointService.Multipart<OuterUpload>): Either<Unit, NoFail>
                }
                @Endpoint.Service
                interface SvcB : EndpointService {
                    @Endpoint(Endpoint.Method.Put)
                    suspend fun Up.put(parts: EndpointService.Multipart<Outer.Upload>): Either<Unit, NoFail>
                }

                @ClientEndpointServiceFactory(SvcA::class, "a")
                @ClientEndpointServiceFactory(SvcB::class, "b")
                object APIServices
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.errors().any { it.contains("both generate the multipart builder name") },
            result.errors().toString(),
        )
    }
}
