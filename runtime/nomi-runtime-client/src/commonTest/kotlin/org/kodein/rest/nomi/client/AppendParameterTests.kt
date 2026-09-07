package org.kodein.rest.nomi.client

import io.ktor.client.request.HttpRequestBuilder
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.Stringer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalNomiAPI::class)
private class AppendParamFakeService : NomiClientEndpointService {
    override val buildRequest: HttpRequestBuilder.() -> Unit = {}
}

private object ReservedCharStringer : Stringer<String> {
    override fun encode(value: String): String = value
    override fun decode(encoded: String) = error("not needed by this test")
}

@OptIn(InternalNomiAPI::class)
class AppendParameterTests {

    @Test
    fun `appendParameter appends the Stringer-encoded value when non-null`() {
        val fake = AppendParamFakeService()
        val builder = HttpRequestBuilder()
        with(fake) {
            builder.appendParameter("page", 3, Stringer.Builtins.IntStringer)
        }
        assertEquals("3", builder.url.parameters["page"])
    }

    @Test
    fun `appendParameter appends nothing when the value is null`() {
        val fake = AppendParamFakeService()
        val builder = HttpRequestBuilder()
        with(fake) {
            builder.appendParameter("page", null, Stringer.Builtins.IntStringer)
        }
        assertNull(builder.url.parameters["page"])
        assertTrue(builder.url.parameters.names().isEmpty())
    }

    @Test
    fun `appendParameter percent-encodes reserved characters produced by the Stringer`() {
        // SPECS.md: a Stringer encodes to unescaped text, and the runtime is responsible for
        // percent-encoding it - a Stringer that escaped its own output would be double-escaped.
        val fake = AppendParamFakeService()
        val builder = HttpRequestBuilder()
        builder.url { host = "example.test" }
        with(fake) {
            builder.appendParameter("q", "a b&c=d", ReservedCharStringer)
        }
        val url = builder.url.build()
        assertEquals("a b&c=d", url.parameters["q"])
        assertTrue(url.encodedQuery.contains("%26")) // '&' would otherwise separate parameters
        assertTrue(url.encodedQuery.contains("%3D")) // '=' would otherwise separate key and value
    }
}
