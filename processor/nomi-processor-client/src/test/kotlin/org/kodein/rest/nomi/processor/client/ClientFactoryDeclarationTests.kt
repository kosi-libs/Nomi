@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.client

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.md § Client factories - the annotated object's own shape rules. */
class ClientFactoryDeclarationTests {

    @Test
    fun `rejects a class, interface or enum annotated with ClientEndpointServiceFactory`() {
        // Not tested here: an annotation class - @ClientEndpointServiceFactory targets only
        // AnnotationTarget.CLASS (no ANNOTATION_CLASS, unlike @Endpoint.UseStringer), so annotating
        // one is invalid Kotlin syntax the compiler itself rejects, never reaching Nomi's processor.
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

                @ClientEndpointServiceFactory(Svc::class, "svc")
                class NotAnObject
                @ClientEndpointServiceFactory(Svc::class, "svc")
                interface NotAnObject2
                @ClientEndpointServiceFactory(Svc::class, "svc")
                enum class NotAnObject3 { ; }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("NotAnObject'") && it.contains("class") }, errors.toString())
        assertTrue(errors.any { it.contains("NotAnObject2") }, errors.toString())
        assertTrue(errors.any { it.contains("NotAnObject3") }, errors.toString())
    }

    @Test
    fun `rejects a companion object`() {
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

                class Holder {
                    @ClientEndpointServiceFactory(Svc::class, "svc")
                    companion object
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("companion object") }, result.errors().toString())
    }

    @Test
    fun `rejects an object nested inside a resource`() {
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

                @Endpoint.Resource
                interface Res {
                    @ClientEndpointServiceFactory(Svc::class, "svc")
                    object Factories
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("resource") }, result.errors().toString())
    }

    @Test
    fun `accepts an object nested inside a plain class`() {
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

                class Namespace {
                    @ClientEndpointServiceFactory(Svc::class, "svc")
                    object Factories
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects a serviceClass that does not name an Endpoint Service interface`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.EndpointService
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                interface NotAService : EndpointService

                @ClientEndpointServiceFactory(NotAService::class, "svc")
                object Factories
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("NotAService") }, result.errors().toString())
    }
}
