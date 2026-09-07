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

private data class CallerTestFail(val reason: String) : Error

@OptIn(InternalNomiAPI::class)
private class FakeEndpointService(
    val client: HttpClient,
    // Named differently from `buildRequest` on purpose: a real domain service interface (like
    // ArticlesService in the demo) never exposes this property - only its NomiClientEndpointService
    // implementation does, under a type the call lambda never sees. Naming it `buildRequest` here
    // would instead shadow CallBuilder.buildRequest inside the call lambda below.
    val applyBuildRequest: HttpRequestBuilder.() -> Unit,
) : EndpointService

private fun dummyClient(): HttpClient = HttpClient(MockEngine { error("the engine should not be reached by this test") })

@Serializable
private data class Payload(val value: Int)

@Serializable
private data class EndToEndFail(val reason: String) : Error

@OptIn(InternalNomiAPI::class)
private class EndToEndService(
    private val client: HttpClient,
    override val buildRequest: HttpRequestBuilder.() -> Unit,
) : NomiClientEndpointService {
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
    fun `every registered buildRequest block is applied - in registration order`() = runTest {
        val applied = mutableListOf<String>()
        lateinit var createdService: FakeEndpointService
        val caller = NomiServiceCaller(client = dummyClient()) { client, buildRequest ->
            FakeEndpointService(client, buildRequest).also { createdService = it }
        }

        caller.call(NType<Unit>(), NType<CallerTestFail>()) {
            contextOf<ServiceCaller.CallBuilder>().buildRequest { applied.add("first") }
            contextOf<ServiceCaller.CallBuilder>().buildRequest { applied.add("second") }
            Either.value(Unit)
        }

        // The service's own buildRequest runs every block the call registered, in order.
        createdService.applyBuildRequest(HttpRequestBuilder())
        assertEquals(listOf("first", "second"), applied)
    }

    @Test
    fun `an end-to-end call over MockEngine mirrors the generated client shape`() = runTest {
        val client = HttpClient(MockEngine { _ ->
            respond(
                content = """{"value":42}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) { json() }
            defaultRequest { url("https://example.test/") }
        }
        val caller: ServiceCaller<EndToEndService> = NomiServiceCaller(client, service = ::EndToEndService)
        val result = caller.call { get() }
        assertEquals(CallStatus.Success(Payload(42)), result)
    }
}
