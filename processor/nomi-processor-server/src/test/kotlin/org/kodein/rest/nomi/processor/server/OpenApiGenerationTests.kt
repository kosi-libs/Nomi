@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SPECS.adoc § Server processor - the `describe { }` block emitted for a service that opts into
 * OpenAPI. Every fact asserted here is one the processor holds before it writes the route, which is
 * why Nomi can state it exactly where ktor's own inference has to guess. */
class OpenApiGenerationTests {

    /** One service, [endpoints] verbatim, with [withOpenApiSpecs] on the implementation. */
    private fun source(
        endpoints: String,
        withOpenApiSpecs: Boolean = true,
        overrides: String = endpoints.replace("suspend fun", "override suspend fun").replace(Regex(":\\s*Either<([^\\n]*)>"), ": Either<$1> = TODO()"),
        extra: String = "",
    ) = SourceFile.kotlin(
        "endpoints.kt",
        """
        package test
        import org.kodein.rest.nomi.Endpoint
        import org.kodein.rest.nomi.EndpointBinary
        import org.kodein.rest.nomi.EndpointFile
        import org.kodein.rest.nomi.EndpointService
        import org.kodein.rest.nomi.Either
        import org.kodein.rest.nomi.NoFail
        import org.kodein.rest.nomi.Error
        import org.kodein.rest.nomi.server.ServerEndpointService
        import org.kodein.rest.nomi.Endpoint.MultipartFormData.PartPresence
        import org.kodein.rest.nomi.Stringer
        import io.ktor.http.ContentType
        import kotlinx.serialization.Serializable

        @Serializable data class Payload(val value: Int)
        @Serializable data class Fail(val reason: String) : Error

        $extra

        @Endpoint.Resource
        interface Res {
            @Endpoint.Resource("r/{id}")
            interface One : Res { val id: Int }
        }

        @Endpoint.Service
        interface Svc : EndpointService {
            $endpoints
        }

        @ServerEndpointService("svc", withOpenApiSpecs = $withOpenApiSpecs)
        class ServerImpl : Svc {
            $overrides
        }
        """,
    )

    private fun generate(vararg sources: SourceFile): String {
        val result = compile(*sources)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        return result.generated("NomiServerImplRoutes.kt")
    }

    @Test
    fun `a service that does not opt in is described not at all`() {
        // The flag is the whole switch: nothing about OpenAPI reaches a service that did not ask,
        // not even the experimental opt-in the API would otherwise require.
        val generated = generate(
            source("suspend fun Res.`get`(): Either<Payload, NoFail>", withOpenApiSpecs = false),
        )

        assertFalse(generated.contains("describe"), generated)
        assertFalse(generated.contains("ExperimentalKtorApi"), generated)
    }

    @Test
    fun `an opted-in service describes each endpoint and opts into the experimental API`() {
        val generated = generate(source("suspend fun Res.`get`(): Either<Payload, NoFail>"))

        assertTrue(generated.contains("}.describe {"), generated)
        assertTrue(generated.contains("ExperimentalKtorApi::class"), generated)
        assertTrue(generated.contains("schema = jsonSchema<Payload>()"), generated)
    }

    @Test
    fun `KDoc on the implementation becomes the summary and description`() {
        val generated = generate(
            source(
                endpoints = "suspend fun Res.`get`(): Either<Payload, NoFail>",
                overrides = """
                    /**
                     * Reads the payload.
                     *
                     * Answers it whole, every time.
                     */
                    override suspend fun Res.`get`(): Either<Payload, NoFail> = TODO()
                """,
            ),
        )

        assertTrue(generated.contains("""summary = "Reads the payload."""), generated)
        assertTrue(generated.contains("""description = "Answers it whole, every time."""), generated)
    }

    @Test
    fun `an endpoint without KDoc carries no summary at all`() {
        // An empty summary would be worse than none: it reads as documented-but-blank.
        val generated = generate(source("suspend fun Res.`get`(): Either<Payload, NoFail>"))

        assertFalse(generated.contains("summary ="), generated)
    }

    @Test
    fun `a NoFail endpoint documents no 422 - it has no business failure`() {
        val generated = generate(source("suspend fun Res.`get`(): Either<Payload, NoFail>"))

        assertFalse(generated.contains("UnprocessableEntity"), generated)
    }

    @Test
    fun `an endpoint with a real error type documents its 422`() {
        val generated = generate(source("suspend fun Res.`get`(): Either<Payload, Fail>"))

        assertTrue(generated.contains("HttpStatusCode.UnprocessableEntity {"), generated)
        assertTrue(generated.contains("schema = jsonSchema<Fail>()"), generated)
    }

    @Test
    fun `a Unit success documents a 204 rather than a 200`() {
        val generated = generate(source("suspend fun Res.`get`(): Either<Unit, NoFail>"))

        assertTrue(generated.contains("HttpStatusCode.NoContent {"), generated)
        assertFalse(generated.contains("HttpStatusCode.OK {"), generated)
    }

    @Test
    fun `a nullable success documents both a 200 and a 204`() {
        // Two real outcomes, and the 200's schema is the non-null type - jsonSchema rejects a
        // nullable argument, and the nullability is what the 204 already says.
        val generated = generate(source("suspend fun Res.`get`(): Either<Payload?, NoFail>"))

        assertTrue(generated.contains("HttpStatusCode.OK {"), generated)
        assertTrue(generated.contains("schema = jsonSchema<Payload>()"), generated)
        assertTrue(generated.contains("HttpStatusCode.NoContent {"), generated)
    }

    @Test
    fun `path and query parameters carry their wire shape and requiredness`() {
        val generated = generate(
            source(
                endpoints = "suspend fun Res.One.`get`(@Endpoint.Query page: Int? = null): Either<Payload, NoFail>",
                // The annotations belong to the declaration; an override restating them is a stray.
                overrides = "override suspend fun Res.One.`get`(page: Int?): Either<Payload, NoFail> = TODO()",
            ),
        )

        assertTrue(generated.contains("""path("id") {"""), generated)
        assertTrue(generated.contains("""query("page") {"""), generated)
        // A path parameter is always required; a nullable query parameter never is.
        assertTrue(generated.contains("required = true"), generated)
        assertTrue(generated.contains("required = false"), generated)
        // Both are Int, whose builtin Stringer really does produce a number on the wire.
        assertTrue(generated.contains("JsonSchema(type = JsonType.INTEGER)"), generated)
        // Decoding either of them can fail, which is the only source of a 400.
        assertTrue(generated.contains("HttpStatusCode.BadRequest {"), generated)
    }

    @Test
    fun `an endpoint with no parameters documents no 400`() {
        val generated = generate(source("suspend fun Res.`get`(): Either<Payload, NoFail>"))

        assertFalse(generated.contains("BadRequest"), generated)
    }

    @Test
    fun `a streamed body and a streamed answer are both binary`() {
        val generated = generate(
            source(
                """
                @Endpoint(Endpoint.Method.Put)
                suspend fun Res.put(data: EndpointBinary): Either<Unit, NoFail>
                suspend fun Res.One.`get`(): Either<EndpointBinary, NoFail>
                """,
                overrides = """
                override suspend fun Res.put(`data`: EndpointBinary): Either<Unit, NoFail> = TODO()
                override suspend fun Res.One.`get`(): Either<EndpointBinary, NoFail> = TODO()
                """,
            ),
        )

        assertEquals(
            2,
            Regex("""JsonSchema\(type = JsonType\.STRING, format = "binary"\)""").findAll(generated).count(),
            generated,
        )
        assertTrue(generated.contains("ContentType.Application.OctetStream {"), generated)
    }

    @Test
    fun `operationIds are unique across a service even when two resources share an endpoint name`() {
        // OpenAPI requires them unique across the document, and two resources of one service may
        // each have a `get` - as the demo's Tools.Headers and Tools.Download do.
        val generated = generate(source("""
            suspend fun Res.`get`(): Either<Payload, NoFail>
            suspend fun Res.One.`get`(): Either<Payload, NoFail>
        """))

        val ids = Regex("""operationId = "([^"]+)"""").findAll(generated).map { it.groupValues[1] }.toList()
        assertEquals(2, ids.size, generated)
        assertEquals(ids.size, ids.toSet().size, "operationIds must be unique, were: $ids")
    }

    @Test
    fun `a hidden endpoint is hidden rather than described`() {
        val generated = generate(
            source(
                endpoints = "suspend fun Res.`get`(): Either<Payload, NoFail>",
                overrides = """
                    @ServerEndpointService.HideFromOpenApi
                    override suspend fun Res.`get`(): Either<Payload, NoFail> = TODO()
                """,
            ),
        )

        assertTrue(generated.contains("}.hide()"), generated)
        assertFalse(generated.contains("}.describe {"), generated)
    }

    @Test
    fun `hiding an endpoint of a service that never opted in is an error`() {
        // It would otherwise do nothing at all, and the author would only find out by reading a
        // document that still has the endpoint in it.
        val result = compile(
            source(
                endpoints = "suspend fun Res.`get`(): Either<Payload, NoFail>",
                withOpenApiSpecs = false,
                overrides = """
                    @ServerEndpointService.HideFromOpenApi
                    override suspend fun Res.`get`(): Either<Payload, NoFail> = TODO()
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.errors().any { it.contains("does not set withOpenApiSpecs") },
            result.errors().toString(),
        )
    }

    @Test
    fun `hiding a function that is not an endpoint override is an error`() {
        val result = compile(
            source(
                endpoints = "suspend fun Res.`get`(): Either<Payload, NoFail>",
                overrides = """
                    override suspend fun Res.`get`(): Either<Payload, NoFail> = TODO()

                    @ServerEndpointService.HideFromOpenApi
                    fun helper() {}
                """,
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.errors().any { it.contains("does not override an endpoint") },
            result.errors().toString(),
        )
    }

    @Test
    fun `a multipart body is an object schema with its parts' cardinality and media types`() {
        val generated = generate(
            source(
                endpoints = "@Endpoint(Endpoint.Method.Post)\nsuspend fun Res.post(parts: EndpointService.Multipart<Up>): Either<Unit, NoFail>",
                overrides = "override suspend fun Res.post(parts: EndpointService.Multipart<Up>): Either<Unit, NoFail> = TODO()",
                extra = """
                    object JsonishStringer : Stringer<Payload> {
                        override fun encode(value: Payload): String = value.value.toString()
                        override fun decode(encoded: String): Either<Payload, Stringer.Failure> = TODO()
                        override val contentType: ContentType get() = ContentType.Application.Json
                    }

                    @Endpoint.MultipartFormData
                    sealed interface Up {
                        data class File(val file: EndpointFile) : Up
                        @Endpoint.MultipartFormData.Part(presence = PartPresence.RepeatedOptional)
                        data class Item(@Endpoint.Stringed(JsonishStringer::class) val item: Payload) : Up
                    }
                """,
            ),
        )

        assertTrue(generated.contains("ContentType.MultiPart.FormData {"), generated)
        assertTrue(generated.contains("type = JsonType.OBJECT"), generated)
        // Only the file part is required; only the item part repeats.
        assertTrue(generated.contains("""required = listOf("file")"""), generated)
        assertTrue(generated.contains("type = JsonType.ARRAY"), generated)
        // The media type is emitted as a reference to the Stringer object, which resolves at runtime.
        assertTrue(generated.contains("""encoding("item", Encoding(contentType = JsonishStringer.contentType))"""), generated)
        assertTrue(generated.contains("""encoding("file", Encoding(contentType = ContentType.Application.OctetStream))"""), generated)
    }
}
