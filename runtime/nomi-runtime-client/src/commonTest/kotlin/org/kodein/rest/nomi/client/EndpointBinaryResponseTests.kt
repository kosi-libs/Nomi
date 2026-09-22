package org.kodein.rest.nomi.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.EndpointBinary
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.mapValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class DownloadFail(val reason: String) : Error

@OptIn(InternalNomiAPI::class)
private class DownloadService(
    private val client: HttpClient,
    buildRequest: ServiceCaller.KtorRequestBuilder?,
    streamingHandler: ServiceCaller.StreamingResponseHandler<*, *, *>?,
) : NomiClientEndpointService(buildRequest, streamingHandler) {
    suspend fun download(): Either<EndpointBinary, DownloadFail> = client.call(listOf("file"), HttpMethod.Get)
}

/**
 * README.adoc § Streaming a response - an endpoint whose success type is an [EndpointBinary] is read
 * off the still-open response, which is why it may only be called through `callStreaming`.
 */
@OptIn(InternalNomiAPI::class)
class EndpointBinaryResponseTests {

    private var requests = 0

    private fun clientAnswering(
        body: String = "hello",
        contentType: String? = "text/plain",
        contentLength: String? = null,
    ) = HttpClient(MockEngine { _ ->
        requests++
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(
                *buildList {
                    contentType?.let { add(HttpHeaders.ContentType to listOf(it)) }
                    contentLength?.let { add(HttpHeaders.ContentLength to listOf(it)) }
                }.toTypedArray()
            ),
        )
    }) {
        install(ContentNegotiation) { json() }
        defaultRequest { url("https://example.test/") }
    }

    private fun caller(client: HttpClient): ServiceCaller<DownloadService> =
        NomiServiceCaller(client, service = ::DownloadService)

    @Test
    fun `the handler reads the body off the still-open response`() = runTest {
        val result = caller(clientAnswering()).callStreaming({ download() }) { get ->
            get().mapValue { binary -> binary.source().use { it.readString() } }
        }

        assertEquals(CallStatus.Success<String, DownloadFail>("hello"), result)
    }

    @Test
    fun `the binary carries the response's content type and length`() = runTest {
        val result = caller(clientAnswering(contentLength = "5")).callStreaming({ download() }) { get ->
            get().mapValue { binary ->
                assertEquals(ContentType.Text.Plain, binary.contentType.withoutParameters())
                assertEquals(5L, binary.size)
                binary.source().use { it.readString() }
            }
        }

        assertEquals(CallStatus.Success<String, DownloadFail>("hello"), result)
    }

    @Test
    fun `an undeclared content type reads as application octet-stream`() = runTest {
        caller(clientAnswering(contentType = null)).callStreaming({ download() }) { get ->
            get().mapValue { binary ->
                assertEquals(ContentType.Application.OctetStream, binary.contentType)
                // No Content-Length on this response, so the size is genuinely unknown.
                assertNull(binary.size)
                binary.source().use { it.readString() }
            }
        }
    }

    @Test
    fun `a plain call fails instead of sending a request it could not stream`() = runTest {
        // `call` reads and closes the response before returning, so there would be nothing left to
        // stream. Caught before anything goes out, and left to propagate as a programmer error
        // rather than folded into a CallStatus.
        val caller = caller(clientAnswering())

        val ex = assertFailsWith<IllegalStateException> { caller.call { download() } }
        assertTrue(
            "must be called with callStreaming" in (ex.message ?: ""),
            "the message should point at callStreaming, was: ${ex.message}",
        )
        assertEquals(0, requests, "the refused call must not have reached the engine")
    }

    @Test
    fun `a flow over such an endpoint fails the same way - it calls through call`() = runTest {
        val caller = caller(clientAnswering())

        assertFailsWith<IllegalStateException> {
            caller.flow { download() }.collect { }
        }
        assertEquals(0, requests)
    }

    @Test
    fun `reading the body after the handler returns fails`() = runTest {
        // The response is only open for the handler's duration, so the binary is closed on the way
        // out - smuggling it past the handler fails loudly rather than reading a closed response.
        var escaped: EndpointBinary? = null

        val result = caller(clientAnswering()).callStreaming({ download() }) { get ->
            get().mapValue { binary ->
                escaped = binary
                "ok"
            }
        }
        assertEquals(CallStatus.Success<String, DownloadFail>("ok"), result)

        val ex = assertFailsWith<IllegalStateException> { escaped!!.source() }
        assertTrue(
            "after being closed" in (ex.message ?: ""),
            "the message should say the binary is spent, was: ${ex.message}",
        )
    }

    @Test
    fun `reading the body twice inside the handler fails`() = runTest {
        caller(clientAnswering()).callStreaming({ download() }) { get ->
            get().mapValue { binary ->
                binary.source().use { it.readString() }
                val ex = assertFailsWith<IllegalStateException> { binary.source() }
                assertTrue("single-pass" in (ex.message ?: ""), ex.message)
                "ok"
            }
        }
    }

    @Test
    fun `a business failure still reaches the handler as a failure`() = runTest {
        val client = HttpClient(MockEngine { _ ->
            respond(
                content = """{"reason":"gone"}""",
                status = HttpStatusCode.UnprocessableEntity,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) { json() }
            defaultRequest { url("https://example.test/") }
        }

        val result = caller(client).callStreaming({ download() }) { get ->
            get().mapValue { "should not be reached" }
        }

        assertEquals(CallStatus.CallFailure<String, DownloadFail>(DownloadFail("gone")), result)
    }
}
