package org.kodein.rest.nomi.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.InternalNomiAPI
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class RespondPayload(val value: Int)

@Serializable
private data class RespondFail(val reason: String) : Error

private interface RespondService : EndpointService {
    suspend fun success(): Either<RespondPayload, RespondFail>
    suspend fun successUnit(): Either<Unit, RespondFail>
    suspend fun successNull(): Either<RespondPayload?, RespondFail>
    suspend fun failure(): Either<RespondPayload, RespondFail>
    suspend fun errorWithMessage(): Either<RespondPayload, RespondFail>
    suspend fun errorWithoutMessage(): Either<RespondPayload, RespondFail>
    suspend fun reservedError(): Either<RespondPayload, RespondFail>
    suspend fun boom(): Either<RespondPayload, RespondFail>
}

private class RespondServiceImpl : RespondService {
    override suspend fun success(): Either<RespondPayload, RespondFail> = Either.value(RespondPayload(42))
    override suspend fun successUnit(): Either<Unit, RespondFail> = Either.unit()
    override suspend fun successNull(): Either<RespondPayload?, RespondFail> = Either.value(null)
    override suspend fun failure(): Either<RespondPayload, RespondFail> = Either.error(RespondFail("bad"))
    override suspend fun errorWithMessage(): Either<RespondPayload, RespondFail> = httpError(HttpStatusCode.NotFound, "missing")
    override suspend fun errorWithoutMessage(): Either<RespondPayload, RespondFail> = httpError(HttpStatusCode.NotFound)
    override suspend fun reservedError(): Either<RespondPayload, RespondFail> = httpError(HttpStatusCode.UnprocessableEntity)
    override suspend fun boom(): Either<RespondPayload, RespondFail> = error("kaboom")
}

@OptIn(InternalNomiAPI::class)
class RespondTests {

    @Test
    fun `a non-null success value responds 200 with the serialized body`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { get("/x") { call.respond(RespondServiceImpl()) { success() } } }
        }
        val response = client.get("/x")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"value":42}""", response.bodyAsText())
    }

    @Test
    fun `a Unit success value responds 204 with an empty body`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { get("/x") { call.respond(RespondServiceImpl()) { successUnit() } } }
        }
        val response = client.get("/x")
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `a null success value responds 204 with an empty body`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { get("/x") { call.respond(RespondServiceImpl()) { successNull() } } }
        }
        val response = client.get("/x")
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `a business failure responds 422 with the serialized error body`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { get("/x") { call.respond(RespondServiceImpl()) { failure() } } }
        }
        val response = client.get("/x")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("""{"reason":"bad"}""", response.bodyAsText())
    }

    @Test
    fun `httpError with a message responds with that status and the message as the body`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { get("/x") { call.respond(RespondServiceImpl()) { errorWithMessage() } } }
        }
        val response = client.get("/x")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("missing", response.bodyAsText())
    }

    @Test
    fun `httpError without a message responds with that status and no body`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { get("/x") { call.respond(RespondServiceImpl()) { errorWithoutMessage() } } }
        }
        val response = client.get("/x")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `httpError refusing UnprocessableEntity is not caught - it surfaces as a 500`() = testApplication {
        // 422 is reserved by Nomi (SPECS.md): httpError's own `require` rejects it before an
        // EndpointStatusCodeException even exists, so respond's catch block never sees it - this
        // is a programmer error, not a business failure.
        application {
            install(ContentNegotiation) { json() }
            routing { get("/x") { call.respond(RespondServiceImpl()) { reservedError() } } }
        }
        val response = client.get("/x")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    @Test
    fun `an unexpected exception from the service is not swallowed - it surfaces as a 500`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { get("/x") { call.respond(RespondServiceImpl()) { boom() } } }
        }
        val response = client.get("/x")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }
}
