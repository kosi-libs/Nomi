package org.kodein.rest.nomi.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.errorOrNull
import org.kodein.rest.nomi.isValue
import org.kodein.rest.nomi.value
import org.kodein.rest.nomi.valueOrNull
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class TestValue(val name: String, val count: Int)

@Serializable
private data class CallTestFail(val reason: String) : Error

// Mirrors how generated client code calls the runtime: a class implementing
// NomiClientEndpointService, with plain (non-generic) suspend member functions calling
// client.call(...) directly - exactly as NomiAPIServicesArticlesServiceClient does in the demo.
// One method per S/F shape needed below, rather than a single generic helper: the `call` context
// parameter resolves through a member's own dispatch receiver only for a plain suspend function,
// not once that function is itself made `inline`/`reified` to forward S and F generically.
@OptIn(InternalNomiAPI::class)
private class FakeService(
    override val buildRequest: HttpRequestBuilder.() -> Unit = {},
) : NomiClientEndpointService {
    suspend fun getValue(
        client: HttpClient,
        pathSegments: List<String>,
        method: HttpMethod = HttpMethod.Get,
        buildRequest: HttpRequestBuilder.() -> Unit = {},
    ): Either<TestValue, CallTestFail> = client.call(pathSegments, method, buildRequest)

    suspend fun getUnit(
        client: HttpClient,
        pathSegments: List<String>,
        method: HttpMethod = HttpMethod.Get,
    ): Either<Unit, CallTestFail> = client.call(pathSegments, method)

    suspend fun getNullableString(
        client: HttpClient,
        pathSegments: List<String>,
        method: HttpMethod = HttpMethod.Get,
    ): Either<String?, CallTestFail> = client.call(pathSegments, method)
}

private fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
    HttpClient(MockEngine(handler)) {
        install(ContentNegotiation) { json() }
        defaultRequest { url("https://example.test/") }
    }

@OptIn(InternalNomiAPI::class)
class HttpClientCallTests {

    @Test
    fun `a 200 response decodes into the success value`() = runTest {
        val client = mockClient { _ ->
            respond(
                content = """{"name":"a","count":1}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = FakeService().getValue(client, listOf("path"))
        assertEquals(TestValue("a", 1), result.value())
    }

    @Test
    fun `a 204 response yields Unit when S is Unit`() = runTest {
        val client = mockClient { _ -> respond(content = "", status = HttpStatusCode.NoContent) }
        val result = FakeService().getUnit(client, listOf("path"))
        assertTrue(result.isValue())
        assertEquals(Unit, result.value())
    }

    @Test
    fun `a 204 response yields null when S is nullable and not Unit`() = runTest {
        val client = mockClient { _ -> respond(content = "", status = HttpStatusCode.NoContent) }
        val result = FakeService().getNullableString(client, listOf("path"))
        assertTrue(result.isValue())
        assertNull(result.valueOrNull())
    }

    @Test
    fun `a 422 response decodes into the business failure`() = runTest {
        val client = mockClient { _ ->
            respond(
                content = """{"reason":"bad"}""",
                status = HttpStatusCode.UnprocessableEntity,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = FakeService().getValue(client, listOf("path"))
        assertEquals(CallTestFail("bad"), result.errorOrNull())
    }

    @Test
    fun `any other status throws EndpointStatusCodeException carrying the status and body`() = runTest {
        val client = mockClient { _ -> respond(content = "server exploded", status = HttpStatusCode.InternalServerError) }
        val ex = assertFailsWith<EndpointStatusCodeException> {
            FakeService().getValue(client, listOf("path"))
        }
        assertEquals(HttpStatusCode.InternalServerError, ex.code)
        assertEquals("server exploded", ex.body)
    }

    @Test
    fun `a transport failure is wrapped in EndpointNetworkException`() = runTest {
        val client = HttpClient(MockEngine { throw RuntimeException("boom") }) {
            install(ContentNegotiation) { json() }
            defaultRequest { url("https://example.test/") }
        }
        val ex = assertFailsWith<EndpointNetworkException> {
            FakeService().getValue(client, listOf("path"))
        }
        assertEquals("boom", ex.message)
        assertIs<RuntimeException>(ex.cause)
    }

    @Test
    fun `a CancellationException from the engine is rethrown unwrapped`() = runTest {
        val client = HttpClient(MockEngine { throw CancellationException("cancelled") }) {
            install(ContentNegotiation) { json() }
            defaultRequest { url("https://example.test/") }
        }
        assertFailsWith<CancellationException> {
            FakeService().getValue(client, listOf("path"))
        }
    }

    @Test
    fun `an IllegalStateException from the engine is rethrown unwrapped - the misconfigured-client case`() = runTest {
        // Regression test for commit e8a1415: a misconfigured client (e.g. missing a required
        // plugin) must surface as a programmer error, not be swallowed into a NetworkFailure.
        val client = HttpClient(MockEngine { error("client not configured") }) {
            install(ContentNegotiation) { json() }
            defaultRequest { url("https://example.test/") }
        }
        val ex = assertFailsWith<IllegalStateException> {
            FakeService().getValue(client, listOf("path"))
        }
        assertEquals("client not configured", ex.message)
    }

    @Test
    fun `path segments are appended to the request URL and percent-encoded`() = runTest {
        var capturedUrl: Url? = null
        var capturedMethod: HttpMethod? = null
        val client = mockClient { request ->
            capturedUrl = request.url
            capturedMethod = request.method
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        FakeService().getUnit(client, listOf("a b", "c"), HttpMethod.Post)

        val url = capturedUrl!!
        assertEquals(listOf("a b", "c"), url.segments)
        assertTrue(url.encodedPath.contains("a%20b"))
        assertEquals(HttpMethod.Post, capturedMethod)
    }

    @Test
    fun `the service buildRequest runs after the endpoint buildRequest and can override it`() = runTest {
        var capturedHeader: String? = null
        val client = mockClient { request ->
            capturedHeader = request.headers[HttpHeaders.XForwardedHost] // any header name works, reused below
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val fake = FakeService(buildRequest = {
            headers.remove(HttpHeaders.XForwardedHost)
            header(HttpHeaders.XForwardedHost, "from-service")
        })
        fake.getValue(client, listOf("path"), HttpMethod.Get) {
            header(HttpHeaders.XForwardedHost, "from-endpoint")
        }

        assertEquals("from-service", capturedHeader)
    }
}
