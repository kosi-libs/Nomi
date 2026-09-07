package org.kodein.rest.nomi.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.uri
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.InternalNomiAPI
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private data class RoutingCallPayload(val uri: String)

@Serializable
private data class RoutingCallFail(val reason: String) : Error

private interface RoutingCallService : EndpointService {
    suspend fun withCall(): Either<RoutingCallPayload, RoutingCallFail>
    suspend fun withNestedContext(): Either<RoutingCallPayload, RoutingCallFail>
}

private class RoutingCallServiceImpl : RoutingCallService {
    override suspend fun withCall(): Either<RoutingCallPayload, RoutingCallFail> =
        Either.value(RoutingCallPayload(routingCall().request.uri))

    override suspend fun withNestedContext(): Either<RoutingCallPayload, RoutingCallFail> = withContext(EmptyCoroutineContext) {
        Either.value(RoutingCallPayload(routingCall().request.uri))
    }
}

private class PlainService : EndpointService

@OptIn(InternalNomiAPI::class)
class RoutingCallTests {

    @Test
    fun `routingCall returns the real call inside a respond block`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { get("/here") { call.respond(RoutingCallServiceImpl()) { withCall() } } }
        }
        val response = client.get("/here")
        assertTrue(response.bodyAsText().contains("/here"))
    }

    @Test
    fun `routingCall survives a nested withContext`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { get("/nested") { call.respond(RoutingCallServiceImpl()) { withNestedContext() } } }
        }
        val response = client.get("/nested")
        assertTrue(response.bodyAsText().contains("/nested"))
    }

    @Test
    fun `routingCall throws outside of a respond block`() = runTest {
        val ex = assertFailsWith<IllegalStateException> {
            with(PlainService()) { routingCall() }
        }
        assertEquals("No RoutingCall in coroutineContext", ex.message)
    }
}
