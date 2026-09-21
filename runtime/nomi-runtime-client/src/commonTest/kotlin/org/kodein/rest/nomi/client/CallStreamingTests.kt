package org.kodein.rest.nomi.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.mapValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
private data class StreamPayload(val value: Int)

@Serializable
private data class StreamFail(val reason: String) : Error

/** Mirrors the shape the client processor generates, so the streaming handler travels the same
 * path it does in a real generated client. */
@OptIn(InternalNomiAPI::class)
private class StreamingService(
    private val client: HttpClient,
    buildRequest: ServiceCaller.KtorRequestBuilder?,
    streamingHandler: ServiceCaller.StreamingResponseHandler<*, *, *>?,
) : NomiClientEndpointService(buildRequest, streamingHandler) {
    suspend fun get(): Either<StreamPayload, StreamFail> = client.call(listOf("thing"), HttpMethod.Get)
}

/** SPECS.adoc/README.adoc § Streaming a response - `callStreaming` keeps the response open for the
 * handler and folds whatever it returns. */
@OptIn(InternalNomiAPI::class)
class CallStreamingTests {

    private fun clientAnswering(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"value":42}""",
    ) = HttpClient(MockEngine { _ ->
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }) {
        install(ContentNegotiation) { json() }
        defaultRequest { url("https://example.test/") }
    }

    private fun caller(client: HttpClient): ServiceCaller<StreamingService> =
        NomiServiceCaller(client, service = ::StreamingService)

    @Test
    fun `the handler result is what the call answers - not the endpoint own value`() = runTest {
        val result = caller(clientAnswering()).callStreaming({ get() }) { get ->
            get().mapValue { "value is ${it.value}" }
        }

        assertEquals(CallStatus.Success<String, StreamFail>("value is 42"), result)
    }

    @Test
    fun `the response is still readable inside the handler`() = runTest {
        // The point of the whole feature: `call` would have read and closed the response before
        // returning. Reading it here proves it is still open - a saved response would have to be
        // re-read from memory, and a closed one would throw.
        var readInside: StreamPayload? = null

        val result = caller(clientAnswering()).callStreaming({ get() }) { get ->
            get().mapValue { payload ->
                readInside = payload
                payload.value
            }
        }

        assertEquals(StreamPayload(42), readInside)
        assertEquals(CallStatus.Success<Int, StreamFail>(42), result)
    }

    @Test
    fun `a business failure reaches the handler and folds to a CallFailure`() = runTest {
        val client = clientAnswering(
            status = HttpStatusCode.UnprocessableEntity,
            body = """{"reason":"nope"}""",
        )
        var mappedTheValue = false

        val result = caller(client).callStreaming({ get() }) { get ->
            get().mapValue { mappedTheValue = true; it.value }
        }

        // The handler ran and was handed the failing Either - its value branch never fired.
        assertFalse(mappedTheValue, "the handler should have seen a failure, not a value")
        assertEquals(CallStatus.CallFailure<Int, StreamFail>(StreamFail("nope")), result)
    }

    @Test
    fun `an unexpected status never reaches the handler and becomes a ServerFailure`() = runTest {
        val client = HttpClient(MockEngine { _ -> respondError(HttpStatusCode.NotFound, "missing") }) {
            install(ContentNegotiation) { json() }
            defaultRequest { url("https://example.test/") }
        }
        var handlerRan = false

        val result = caller(client).callStreaming({ get() }) { get ->
            handlerRan = true
            get().mapValue { it.value }
        }

        // The handler is entered, but `get()` throws through it rather than answering.
        assertTrue(handlerRan)
        assertEquals(CallStatus.ServerFailure<Int, StreamFail>(HttpStatusCode.NotFound, "missing"), result)
    }

    @Test
    fun `a transport failure becomes a NetworkFailure`() = runTest {
        val client = HttpClient(MockEngine { _ -> throw RuntimeException("boom") }) {
            defaultRequest { url("https://example.test/") }
        }

        val result = caller(client).callStreaming({ get() }) { get -> get().mapValue { it.value } }

        assertTrue(result is CallStatus.NetworkFailure, result.toString())
    }

    @Test
    fun `a second call inside one streaming call fails - the guard is what makes the handler cast sound`() = runTest {
        // Not merely the same rule as a plain call. HttpClient.call narrows the star-projected
        // handler back to <S, Any, F> using its own type parameters, which holds only because one
        // instance sees one endpoint: two endpoints in one lambda would hand a single handler two
        // different S/F pairs. The guard is what rules that out, so relaxing it would silently
        // unsound the cast rather than merely allow a second request.
        val caller = caller(clientAnswering())

        val ex = assertFailsWith<IllegalStateException> {
            caller.callStreaming({
                get()
                get()
            }) { get -> get().mapValue { it.value } }
        }
        assertTrue(
            "can only call one service method" in (ex.message ?: ""),
            "the message should say an instance serves a single call, was: ${ex.message}",
        )
    }

    @Test
    fun `a buildRequest given to callStreaming is applied to the request`() = runTest {
        var seenHeader: String? = null
        val client = HttpClient(MockEngine { request ->
            seenHeader = request.headers["X-Nomi-Test"]
            respond(
                content = """{"value":1}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) { json() }
            defaultRequest { url("https://example.test/") }
        }

        caller(client).callStreaming(
            { get() },
            buildRequest = { headers.append("X-Nomi-Test", "yes") },
        ) { get -> get().mapValue { it.value } }

        assertEquals("yes", seenHeader)
    }
}
