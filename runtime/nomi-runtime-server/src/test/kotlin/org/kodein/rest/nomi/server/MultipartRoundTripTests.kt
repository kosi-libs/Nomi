package org.kodein.rest.nomi.server

import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.server.request.receiveMultipart
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.io.Buffer
import kotlinx.io.readString
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.Endpoint.MultipartFormData.PartPresence
import org.kodein.rest.nomi.EndpointFile
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.NoFail
import org.kodein.rest.nomi.Stringer
import org.kodein.rest.nomi.forEachPart
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises [EndpointMultipartReceiver] against ktor's real multipart parser, on a real request
 * body, so that part lifetime, draining and required-part checking are proven end to end rather
 * than against a fake [MultiPartData].
 */
private sealed interface RoundTripPart {
    data class File(val file: EndpointFile) : RoundTripPart
    data class From(val from: String) : RoundTripPart
}

private class RoundTripService : EndpointService

@OptIn(InternalNomiAPI::class)
private fun RoundTripService.receiver(multipart: MultiPartData): EndpointService.Multipart<RoundTripPart> =
    EndpointMultipartReceiver(
        service = this,
        multipart = multipart,
        parts = mapOf(
            "file" to PartPresence.RepeatedRequired,
            "from" to PartPresence.Required,
        ),
    ) { part ->
        when (part.name) {
            "file" -> RoundTripPart.File(part.asEndpointFile("file"))
            "from" -> RoundTripPart.From(part.asEndpointValue("from", Stringer.Builtins.StringStringer))
            else -> httpError(HttpStatusCode.BadRequest, "Unknown part '${part.name}'")
        }
    }

/**
 * Mirrors byte for byte what `appendEndpointFile` puts on the wire: *parameters only*, with a
 * quoted file name and a size, never a `Content-Disposition` of its own — ktor's `formData` writes
 * the `form-data; name="file"` part, and the two are joined into one header.
 *
 * `nomi-runtime-client` is not on this module's test classpath, so the format is restated here
 * rather than produced by the real encoder; `AppendEndpointPartTests` pins the encoder's output to
 * this same string.
 */
private fun filePart(content: String): List<PartData> = formData {
    val bytes = content.encodeToByteArray()
    append(
        key = "file",
        value = InputProvider(bytes.size.toLong()) { Buffer().apply { write(bytes) } },
        headers = Headers.build {
            append(
                HttpHeaders.ContentDisposition,
                """${ContentDisposition.Parameters.FileName}="a.txt"; ${ContentDisposition.Parameters.Size}=${bytes.size}""",
            )
        },
    )
}

/** Mirrors what the generated client emits for a stringer-encoded part, Content-Type included. */
private fun fromPart(value: String): List<PartData> = formData {
    append(
        key = "from",
        value = value,
        headers = Headers.build {
            append(HttpHeaders.ContentType, Stringer.Builtins.StringStringer.contentType.toString())
        },
    )
}

@OptIn(InternalNomiAPI::class)
class MultipartRoundTripTests {

    private fun test(
        parts: List<PartData>,
        handle: suspend RoundTripService.(EndpointService.Multipart<RoundTripPart>) -> Either<String, NoFail>,
        assert: suspend (HttpStatusCode, String) -> Unit,
    ) = testApplication {
        val service = RoundTripService()
        routing {
            post("/upload") {
                call.respond(service) { service.handle(service.receiver(call.receiveMultipart())) }
            }
        }
        val response = client.post("/upload") { setBody(MultiPartFormDataContent(parts)) }
        assert(response.status, response.bodyAsText())
    }

    /** Reads every part, consuming each file's content while still inside its handler. */
    private suspend fun RoundTripService.readAll(
        parts: EndpointService.Multipart<RoundTripPart>,
    ): Either<String, NoFail> {
        val seen = mutableListOf<String>()
        parts.forEachPart { part ->
            seen += when (part) {
                is RoundTripPart.File -> "File(${part.file.source().use { it.readString() }})"
                is RoundTripPart.From -> "From(${part.from})"
            }
        }
        return Either.value(seen.joinToString())
    }

    /** Leaves at the first part: the required-part check must not run. */
    private suspend fun RoundTripService.readFirst(
        parts: EndpointService.Multipart<RoundTripPart>,
    ): Either<String, NoFail> {
        parts.forEachPart { part ->
            return Either.value(
                when (part) {
                    is RoundTripPart.File -> "stopped at File(${part.file.source().use { it.readString() }})"
                    is RoundTripPart.From -> "stopped at From(${part.from})"
                }
            )
        }
        return Either.value("no parts")
    }

    /** Keeps the file past its part, which is exactly what the lifetime rule forbids. */
    private suspend fun RoundTripService.leakFile(
        parts: EndpointService.Multipart<RoundTripPart>,
    ): Either<String, NoFail> {
        var escaped: EndpointFile? = null
        parts.forEachPart { part -> if (part is RoundTripPart.File) escaped = part.file }
        return Either.value(escaped!!.source().use { it.readString() })
    }

    @Test
    fun `reading every part of a complete body succeeds`() = test(
        parts = filePart("hello") + fromPart("me"),
        handle = { readAll(it) },
    ) { status, body ->
        assertEquals(HttpStatusCode.OK, status, body)
        assertEquals("File(hello), From(me)", body)
    }

    @Test
    fun `reading every part reports a missing required part as 400`() = test(
        parts = filePart("hello"),
        handle = { readAll(it) },
    ) { status, body ->
        assertEquals(HttpStatusCode.BadRequest, status, body)
        assertEquals("Missing required part 'from'", body)
    }

    @Test
    fun `returning early does not report a missing required part`() = test(
        parts = filePart("hello"),
        handle = { readFirst(it) },
    ) { status, body ->
        assertEquals(HttpStatusCode.OK, status, body)
        assertEquals("stopped at File(hello)", body)
    }

    @Test
    fun `returning early drains a body whose remaining parts were never read`() = test(
        parts = filePart("hello") + fromPart("me") + filePart("unread"),
        handle = { readFirst(it) },
    ) { status, body ->
        assertEquals(HttpStatusCode.OK, status, body)
        assertEquals("stopped at File(hello)", body)
    }

    @Test
    fun `a repeated part arrives once per occurrence and still satisfies the required check`() = test(
        // Several files under one part name. 'file' is required; sending it twice must not confuse
        // the required-part check, and neither occurrence may be dropped.
        parts = filePart("first") + fromPart("me") + filePart("second"),
        handle = { readAll(it) },
    ) { status, body ->
        assertEquals(HttpStatusCode.OK, status, body)
        assertEquals("File(first), From(me), File(second)", body)
    }

    @Test
    fun `a part sent without a Content-Type is accepted`() = test(
        // What a browser form, or `curl -F from=me`, actually sends: no Content-Type at all.
        parts = filePart("hello") + formData { append("from", "me") },
        handle = { readAll(it) },
    ) { status, body ->
        assertEquals(HttpStatusCode.OK, status, body)
        assertEquals("File(hello), From(me)", body)
    }

    @Test
    fun `a part whose Content-Type carries a charset is accepted`() = test(
        parts = filePart("hello") + formData {
            append("from", "me", Headers.build { append(HttpHeaders.ContentType, "text/plain; charset=utf-8") })
        },
        handle = { readAll(it) },
    ) { status, body ->
        assertEquals(HttpStatusCode.OK, status, body)
        assertEquals("File(hello), From(me)", body)
    }

    @Test
    fun `a part whose Content-Type contradicts the stringer is rejected`() = test(
        parts = filePart("hello") + formData {
            append("from", "me", Headers.build { append(HttpHeaders.ContentType, "application/json") })
        },
        handle = { readAll(it) },
    ) { status, body ->
        assertEquals(HttpStatusCode.BadRequest, status, body)
        assertEquals("Expected part 'from' content type to be text/plain, but was application/json", body)
    }

    @Test
    fun `reading a file after its part was released fails the endpoint`() = test(
        parts = filePart("hello") + fromPart("me"),
        handle = { leakFile(it) },
    ) { status, _ ->
        // The guard throws IllegalStateException, which respond() does not catch, so ktor answers 500.
        assertEquals(HttpStatusCode.InternalServerError, status)
    }
}
