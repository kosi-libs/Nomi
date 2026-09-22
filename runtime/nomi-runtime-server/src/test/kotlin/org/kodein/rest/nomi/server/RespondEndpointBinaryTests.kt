package org.kodein.rest.nomi.server

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.io.Buffer
import kotlinx.serialization.Serializable
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.EndpointBinary
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.InternalNomiAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class BinaryFail(val reason: String) : Error

private class BinaryRespondService : EndpointService

private fun binary(
    content: String,
    size: Long? = content.length.toLong(),
    contentType: ContentType = ContentType.Text.Plain,
) = EndpointBinary(
    size = size,
    contentType = contentType,
    source = { Buffer().apply { write(content.encodeToByteArray()) } },
)

/**
 * Exercises [respondEndpointBinary] against a real request, in the shape the generated route emits:
 * the endpoint hands back the binary and the runtime writes it, closing it once it is out.
 */
@OptIn(InternalNomiAPI::class)
class RespondEndpointBinaryTests {

    private fun test(
        answer: suspend BinaryRespondService.() -> Either<EndpointBinary, BinaryFail>,
        assert: suspend (HttpResponse) -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/download") {
                    call.respondEndpointBinary(BinaryRespondService(), answer)
                }
            }
        }
        assert(client.get("/download"))
    }

    @Test
    fun `the bytes go out with the binary's own content type and length`() = test(
        answer = { Either.value(binary("hello")) },
    ) { response ->
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("hello", response.bodyAsText())
        assertEquals(ContentType.Text.Plain, response.contentType()?.withoutParameters())
        assertEquals("5", response.headers[HttpHeaders.ContentLength])
    }

    @Test
    fun `nothing is serialized - the bytes are the body verbatim`() = test(
        // ContentNegotiation is installed above and would have quoted and escaped this had the
        // binary gone through respond instead.
        answer = { Either.value(binary("""{"not":"json to Nomi"}""", contentType = ContentType.Application.Json)) },
    ) { response ->
        assertEquals("""{"not":"json to Nomi"}""", response.bodyAsText())
    }

    @Test
    fun `an unknown size goes out chunked rather than claiming a length`() = test(
        answer = { Either.value(binary("hello", size = null)) },
    ) { response ->
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("hello", response.bodyAsText())
        // The honest answer for a stream whose length is not known until it ends.
        assertNull(response.headers[HttpHeaders.ContentLength])
        assertEquals("chunked", response.headers[HttpHeaders.TransferEncoding])
    }

    @Test
    fun `the binary is closed once the response is written`() = testApplication {
        // Whatever the service opened to produce the bytes is released on the way out, and a read
        // that outlives the response fails loudly rather than reading a spent handle.
        var sent: EndpointBinary? = null
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/download") {
                    call.respondEndpointBinary(BinaryRespondService()) {
                        Either.value(binary("hello").also { sent = it })
                    }
                }
            }
        }
        assertEquals("hello", client.get("/download").bodyAsText())

        val ex = assertFailsWith<IllegalStateException> { sent!!.source() }
        assertTrue(
            "after being closed" in (ex.message ?: ""),
            "the message should say the binary is spent, was: ${ex.message}",
        )
    }

    @Test
    fun `a business failure is still a serialized 422`() = test(
        answer = { Either.error(BinaryFail("nope")) },
    ) { response ->
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("""{"reason":"nope"}""", response.bodyAsText())
    }

    @Test
    fun `an httpError answers its own status`() = test(
        answer = { httpError(HttpStatusCode.NotFound, "missing") },
    ) { response ->
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("missing", response.bodyAsText())
    }

    @Test
    fun `an empty binary is an empty 200 rather than a 204`() = test(
        // A zero-length body is still a body: the endpoint answered bytes, there just were none.
        answer = { Either.value(binary("")) },
    ) { response ->
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, response.readRawBytes().size)
    }
}
