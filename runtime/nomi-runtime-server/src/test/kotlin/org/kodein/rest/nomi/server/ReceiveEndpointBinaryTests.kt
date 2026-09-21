package org.kodein.rest.nomi.server

import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.io.readString
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.EndpointBinary
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.NoFail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class BinaryService : EndpointService

/**
 * Exercises [receiveEndpointBinary] against a real request, in the shape the generated route emits:
 * receive before `respond`, close in a `finally` afterwards.
 */
@OptIn(InternalNomiAPI::class)
class ReceiveEndpointBinaryTests {

    private fun test(
        body: String,
        declaredContentType: ContentType? = ContentType.Text.Plain,
        handle: suspend BinaryService.(EndpointBinary) -> Either<String, NoFail>,
        assert: suspend (HttpStatusCode, String) -> Unit,
    ) = testApplication {
        val service = BinaryService()
        routing {
            put("/upload") {
                val binary = call.receiveEndpointBinary(service)
                try {
                    call.respond(service) { service.handle(binary) }
                } finally {
                    binary.close()
                }
            }
        }
        val response = client.put("/upload") {
            declaredContentType?.let { contentType(it) }
            setBody(body)
        }
        assert(response.status, response.bodyAsText())
    }

    @Test
    fun `the service sees the body's bytes, content type and length`() = test(
        body = "hello",
        handle = { binary ->
            assertEquals(ContentType.Text.Plain, binary.contentType.withoutParameters())
            assertEquals(5L, binary.size)
            Either.value(binary.source().use { it.readString() })
        },
    ) { status, body ->
        assertEquals(HttpStatusCode.OK, status, body)
        assertEquals("hello", body)
    }

    @Test
    fun `an undeclared binary body arrives as application octet-stream`() = testApplication {
        // ktor's own client always attaches a Content-Type - a String body becomes text/plain, and a
        // raw channel becomes octet-stream. The helper's ContentType.Any mapping is therefore only
        // reachable from a peer that sends no header at all (curl without -H); what is testable
        // here is that an undeclared stream arrives as octet-stream rather than as text.
        val service = BinaryService()
        routing {
            put("/upload") {
                val binary = call.receiveEndpointBinary(service)
                try {
                    call.respond(service) {
                        assertEquals(ContentType.Application.OctetStream, binary.contentType)
                        Either.value<String, NoFail>(binary.source().use { it.readString() })
                    }
                } finally {
                    binary.close()
                }
            }
        }
        val response = client.put("/upload") {
            setBody(io.ktor.utils.io.ByteReadChannel("hello".encodeToByteArray()))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("hello", response.bodyAsText())
    }

    @Test
    fun `reading the body twice fails rather than returning nothing`() = test(
        body = "hello",
        handle = { binary ->
            binary.source().use { it.readString() }
            val ex = assertFailsWith<IllegalStateException> { binary.source() }
            assertTrue("single-pass" in (ex.message ?: ""), ex.message)
            Either.value("ok")
        },
    ) { status, body -> assertEquals(HttpStatusCode.OK, status, body) }

    @Test
    fun `reading the body after the endpoint returns fails`() = testApplication {
        val service = BinaryService()
        var escaped: EndpointBinary? = null
        routing {
            put("/upload") {
                val binary = call.receiveEndpointBinary(service)
                try {
                    call.respond(service) {
                        escaped = binary
                        Either.value<String, NoFail>("ok")
                    }
                } finally {
                    binary.close()
                }
            }
        }
        assertEquals(HttpStatusCode.OK, client.put("/upload") { setBody("hello") }.status)

        val ex = assertFailsWith<IllegalStateException> { escaped!!.source() }
        assertTrue("after being closed" in (ex.message ?: ""), ex.message)
    }

    @Test
    fun `a chunked body has an unknown size`() = testApplication {
        val service = BinaryService()
        routing {
            put("/upload") {
                val binary = call.receiveEndpointBinary(service)
                try {
                    call.respond(service) {
                        // No Content-Length on a chunked request, so size is genuinely unknown.
                        assertNull(binary.size)
                        Either.value<String, NoFail>(binary.source().use { it.readString() })
                    }
                } finally {
                    binary.close()
                }
            }
        }
        val response = client.put("/upload") {
            setBody(io.ktor.utils.io.ByteReadChannel("chunked!".encodeToByteArray()))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("chunked!", response.bodyAsText())
    }
}
