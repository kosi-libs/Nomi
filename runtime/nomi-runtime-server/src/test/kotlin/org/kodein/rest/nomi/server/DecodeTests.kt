package org.kodein.rest.nomi.server

import io.ktor.http.HttpStatusCode
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.Stringer.Builtins.IntStringer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class DecodeFakeService : EndpointService

@OptIn(InternalNomiAPI::class)
class DecodeTests {

    @Test
    fun `decodeOrNull returns null for a null input`() {
        with(DecodeFakeService()) {
            assertNull(decodeOrNull(IntStringer, null))
        }
    }

    @Test
    fun `decodeOrNull decodes a valid string`() {
        with(DecodeFakeService()) {
            assertEquals(42, decodeOrNull(IntStringer, "42"))
        }
    }

    @Test
    fun `decodeOrNull throws BadRequest carrying the Stringer failure reason`() {
        with(DecodeFakeService()) {
            val ex = assertFailsWith<EndpointStatusCodeException> { decodeOrNull(IntStringer, "nope") }
            assertEquals(HttpStatusCode.BadRequest, ex.code)
            assertEquals("Invalid integer number", ex.message)
        }
    }

    @Test
    fun `decode decodes a valid string`() {
        with(DecodeFakeService()) {
            assertEquals(42, decode(IntStringer, "42"))
        }
    }

    @Test
    fun `decode throws BadRequest carrying the Stringer failure reason`() {
        with(DecodeFakeService()) {
            val ex = assertFailsWith<EndpointStatusCodeException> { decode(IntStringer, "nope") }
            assertEquals(HttpStatusCode.BadRequest, ex.code)
            assertEquals("Invalid integer number", ex.message)
        }
    }
}
