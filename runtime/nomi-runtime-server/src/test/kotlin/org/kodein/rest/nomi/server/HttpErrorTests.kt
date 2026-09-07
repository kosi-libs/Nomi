package org.kodein.rest.nomi.server

import io.ktor.http.HttpStatusCode
import org.kodein.rest.nomi.EndpointService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class FakeService : EndpointService

class HttpErrorTests {

    @Test
    fun `httpError throws EndpointStatusCodeException with the given code and message`() {
        val ex = assertFailsWith<EndpointStatusCodeException> {
            with(FakeService()) { httpError(HttpStatusCode.NotFound, "missing") }
        }
        assertEquals(HttpStatusCode.NotFound, ex.code)
        assertEquals("missing", ex.message)
    }

    @Test
    fun `httpError defaults to a null message`() {
        val ex = assertFailsWith<EndpointStatusCodeException> {
            with(FakeService()) { httpError(HttpStatusCode.NotFound) }
        }
        assertNull(ex.message)
    }

    @Test
    fun `httpError refuses UnprocessableEntity because 422 is reserved by Nomi`() {
        assertFailsWith<IllegalArgumentException> {
            with(FakeService()) { httpError(HttpStatusCode.UnprocessableEntity) }
        }
    }
}
