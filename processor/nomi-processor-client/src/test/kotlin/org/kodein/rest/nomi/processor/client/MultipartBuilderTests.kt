@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.client

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val PARTS = SourceFile.kotlin(
    "parts.kt",
    """
    package test
    import org.kodein.rest.nomi.Endpoint
    import org.kodein.rest.nomi.Endpoint.MultipartFormData.PartPresence
    import org.kodein.rest.nomi.EndpointFile
    import org.kodein.rest.nomi.EndpointService
    import org.kodein.rest.nomi.Either
    import org.kodein.rest.nomi.NoFail

    @Endpoint.Resource
    interface Up

    @Endpoint.MultipartFormData
    sealed interface Upload {
        data class File(val file: EndpointFile) : Upload
        @Endpoint.MultipartFormData.Part(presence = PartPresence.Optional)
        data class From(val from: String) : Upload
        @Endpoint.MultipartFormData.Part(name = "note", presence = PartPresence.RepeatedRequired)
        data class With(val text: String) : Upload
    }

    @Endpoint.Service
    interface Svc : EndpointService {
        @Endpoint(Endpoint.Method.Post)
        suspend fun Up.post(parts: EndpointService.Multipart<Upload>): Either<Unit, NoFail>
    }

    @Endpoint.Service
    interface OtherSvc : EndpointService {
        @Endpoint(Endpoint.Method.Put)
        suspend fun Up.put(parts: EndpointService.Multipart<Upload>): Either<Unit, NoFail>
    }
    """,
)

/** SPECS.adoc § Client processor: builders are collected per annotated object, by part type. */
class MultipartBuilderTests {

    @Test
    fun `generates one builder for two services on one object`() {
        val result = compile(
            PARTS,
            SourceFile.kotlin(
                "factory.kt",
                """
                package test
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @ClientEndpointServiceFactory(Svc::class, "svc")
                @ClientEndpointServiceFactory(OtherSvc::class, "other")
                object APIServices
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiAPIServicesMultipart.kt")
        assertEquals(1, Regex("fun APIServices\\.multipartUpload").findAll(generated).count(), generated)
    }

    @Test
    fun `generates one builder on each of two objects`() {
        val result = compile(
            PARTS,
            SourceFile.kotlin(
                "factory.kt",
                """
                package test
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @ClientEndpointServiceFactory(Svc::class, "svc")
                object FirstFactory

                @ClientEndpointServiceFactory(OtherSvc::class, "other")
                object SecondFactory
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        assertEquals(
            1,
            Regex("fun FirstFactory\\.multipartUpload").findAll(result.generated("NomiFirstFactoryMultipart.kt")).count(),
        )
        assertEquals(
            1,
            Regex("fun SecondFactory\\.multipartUpload").findAll(result.generated("NomiSecondFactoryMultipart.kt")).count(),
        )
    }

    @Test
    fun `the builder's parameter shape follows each part's presence`() {
        val result = compile(
            PARTS,
            SourceFile.kotlin(
                "factory.kt",
                """
                package test
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @ClientEndpointServiceFactory(Svc::class, "svc")
                object APIServices
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiAPIServicesMultipart.kt")
        assertTrue(generated.contains("`file`: EndpointFile,"), generated)          // Required
        assertTrue(generated.contains("from: String? = null,"), generated)          // Optional
        assertTrue(generated.contains("with: List<String>,"), generated)            // RepeatedRequired
        assertTrue(generated.contains("""require(with.isNotEmpty())"""), generated)
    }

    @Test
    fun `the generated client encodes each part under its wire name`() {
        val result = compile(
            PARTS,
            SourceFile.kotlin(
                "factory.kt",
                """
                package test
                import org.kodein.rest.nomi.client.ClientEndpointServiceFactory

                @ClientEndpointServiceFactory(Svc::class, "svc")
                object APIServices
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiAPIServicesSvcClient.kt")
        assertTrue(generated.contains("""appendEndpointFile("file", part.`file`)"""), generated)
        assertTrue(generated.contains("""appendEndpointItem("note", part.text,"""), generated)
        // Exhaustive over the sealed type - no else branch.
        assertTrue(!generated.contains("else ->"), generated)
    }
}
