package org.kodein.rest.nomi.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.NType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private data class CallerTestFail(val reason: String) : Error

@OptIn(InternalNomiAPI::class)
private class FakeEndpointService(
    val client: HttpClient,
    // Named differently from the ServiceCaller.call parameter it's constructed from, purely to
    // keep "the factory argument" and "the field storing it" visually distinct below.
    val applyBuildRequest: ServiceCaller.KtorRequestBuilder?,
    val streamResponse: ServiceCaller.StreamingResponseHandler<*, *, *>?,
) : EndpointService

private fun dummyClient(): HttpClient = HttpClient(MockEngine { error("the engine should not be reached by this test") })

/** Answers every request with a [Payload], for the tests that run a real call through the caller. */
private fun payloadClient(): HttpClient = HttpClient(MockEngine { _ ->
    respond(
        content = """{"value":42}""",
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}) {
    install(ContentNegotiation) { json() }
    defaultRequest { url("https://example.test/") }
}

@Serializable
private data class Payload(val value: Int)

@Serializable
private data class EndToEndFail(val reason: String) : Error

@OptIn(InternalNomiAPI::class)
private class EndToEndService(
    private val client: HttpClient,
    buildRequest: ServiceCaller.KtorRequestBuilder?,
    streamingHandler: ServiceCaller.StreamingResponseHandler<*, *, *>?,
) : NomiClientEndpointService(buildRequest, streamingHandler) {
    suspend fun get(): Either<Payload, EndToEndFail> = client.call(listOf("thing"), HttpMethod.Get)
}

@OptIn(InternalNomiAPI::class)
class NomiServiceCallerTests {

    @Test
    fun `an Either value becomes a CallStatus Success`() = runTest {
        val caller = NomiServiceCaller(client = dummyClient(), service = ::FakeEndpointService)
        val result = caller.call(NType<Int>(), NType<CallerTestFail>()) { Either.value(5) }
        assertEquals(CallStatus.Success<Int, CallerTestFail>(5), result)
    }

    @Test
    fun `an Either error becomes a CallStatus CallFailure`() = runTest {
        val caller = NomiServiceCaller(client = dummyClient(), service = ::FakeEndpointService)
        val result = caller.call(NType<Int>(), NType<CallerTestFail>()) { Either.error(CallerTestFail("bad")) }
        assertEquals(CallStatus.CallFailure(CallerTestFail("bad")), result)
    }

    @Test
    fun `an EndpointStatusCodeException becomes a ServerFailure`() = runTest {
        val caller = NomiServiceCaller(client = dummyClient(), service = ::FakeEndpointService)
        val result = caller.call(NType<Int>(), NType<CallerTestFail>()) {
            throw EndpointStatusCodeException(HttpStatusCode.NotFound, "missing")
        }
        assertEquals(CallStatus.ServerFailure<Int, CallerTestFail>(HttpStatusCode.NotFound, "missing"), result)
    }

    @Test
    fun `an EndpointNetworkException becomes a NetworkFailure`() = runTest {
        val caller = NomiServiceCaller(client = dummyClient(), service = ::FakeEndpointService)
        val cause = RuntimeException("oops")

        val result = caller.call(NType<Int>(), NType<CallerTestFail>()) { throw EndpointNetworkException("boom", cause) }
        assertEquals(CallStatus.NetworkFailure<Int, CallerTestFail>("boom", cause), result)
    }

    @Test
    fun `an EndpointNetworkException with a null message becomes an empty-string NetworkFailure`() = runTest {
        val caller = NomiServiceCaller(client = dummyClient(), service = ::FakeEndpointService)
        val cause = RuntimeException("oops")

        val result = caller.call(NType<Int>(), NType<CallerTestFail>()) { throw EndpointNetworkException(null, cause) }
        assertEquals(CallStatus.NetworkFailure<Int, CallerTestFail>("", cause), result)
    }

    @Test
    fun `any other exception propagates rather than being swallowed into a CallStatus`() = runTest {
        val caller = NomiServiceCaller(client = dummyClient(), service = ::FakeEndpointService)
        assertFailsWith<IllegalStateException> {
            caller.call(NType<Int>(), NType<CallerTestFail>()) { error("oops") }
        }
    }

    @Test
    fun `a buildRequest passed to call is applied to the constructed service`() = runTest {
        val applied = mutableListOf<String>()
        lateinit var createdService: FakeEndpointService
        val caller = NomiServiceCaller(client = dummyClient()) { client, buildRequest, streamResponse ->
            FakeEndpointService(client, buildRequest, streamResponse).also { createdService = it }
        }

        caller.call(NType<Unit>(), NType<CallerTestFail>(), buildRequest = { applied.add("applied") }) {
            Either.value(Unit)
        }

        // The service is constructed with the call's own buildRequest.
        createdService.applyBuildRequest!!(HttpRequestBuilder())
        assertEquals(listOf("applied"), applied)
    }

    @Test
    fun `an end-to-end call over MockEngine mirrors the generated client shape`() = runTest {
        val caller: ServiceCaller<EndToEndService> = NomiServiceCaller(payloadClient(), service = ::EndToEndService)
        val result = caller.call { get() }
        assertEquals(CallStatus.Success(Payload(42)), result)
    }

    @Test
    fun `a call lambda invoking two endpoints fails instead of silently reusing one instance`() = runTest {
        // README § Calling: a call block invokes exactly one endpoint method. The service instance it
        // runs on serves one request, so the second call throws rather than quietly sharing the first
        // call's instance - and it propagates, like any other programmer error, instead of folding
        // into a CallStatus.
        val caller: ServiceCaller<EndToEndService> = NomiServiceCaller(payloadClient(), service = ::EndToEndService)

        val ex = assertFailsWith<IllegalStateException> {
            caller.call {
                get()
                get()
            }
        }
        assertTrue(
            "can only call one service method" in (ex.message ?: ""),
            "the message should say an instance serves a single call, was: ${ex.message}",
        )
    }

    @Test
    fun `one ServiceCaller serves repeated calls - each on a fresh service instance`() = runTest {
        // The companion invariant of that guard: instances being single-use, the caller has to build
        // one per call. Sharing one - which is what this class did before the guard existed - would
        // make every call after the first trip it.
        val caller: ServiceCaller<EndToEndService> = NomiServiceCaller(payloadClient(), service = ::EndToEndService)

        assertEquals(CallStatus.Success(Payload(42)), caller.call { get() })
        assertEquals(CallStatus.Success(Payload(42)), caller.call { get() })
    }
}
