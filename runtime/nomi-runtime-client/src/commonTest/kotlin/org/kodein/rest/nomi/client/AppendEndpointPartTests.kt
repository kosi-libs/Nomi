package org.kodein.rest.nomi.client

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import org.kodein.rest.nomi.EndpointFile
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.Stringer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(InternalNomiAPI::class)
// The helpers under test are members of NomiClientEndpointService, so they need a dispatch
// receiver: hence the `with(fake)` in every test below. This fake carries no per-call state of
// its own - neither a request customization nor a streaming handler is involved in encoding.
private class AppendPartFakeService : NomiClientEndpointService(buildRequest = null, streamingHandler = null)

/**
 * Pins what the client actually puts on the wire.
 *
 * ktor's `formData` writes a `Content-Disposition` of its own and appends whatever a part adds as a
 * further value of the same header, which `MultiPartFormDataContent` then joins with `"; "`. That
 * join is invisible from the resulting [io.ktor.http.content.PartData] objects — it shows up only
 * in the serialized body — so the header has to be asserted on the bytes.
 */
@OptIn(InternalNomiAPI::class)
class AppendEndpointPartTests {

    private val fake = AppendPartFakeService()

    /** Serializes a multipart body the way an engine would, and returns it as text. */
    private suspend fun bodyOf(content: MultiPartFormDataContent): String = coroutineScope {
        val channel = ByteChannel(autoFlush = true)
        launch {
            content.writeTo(channel)
            channel.flushAndClose()
        }
        channel.readRemaining().readString()
    }

    private fun String.dispositionLines(): List<String> =
        lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.startsWith("${HttpHeaders.ContentDisposition}:", ignoreCase = true) }
            .toList()

    private fun file(fileName: String, size: Long? = 5L) = EndpointFile(
        fileName = fileName,
        fileSize = size,
        contentType = ContentType.Text.Plain,
        source = { Buffer().apply { write("hello".encodeToByteArray()) } },
    )

    @Test
    fun `a file part carries exactly one well formed Content-Disposition`() = runTest {
        val content = with(fake) {
            MultiPartFormDataContent(formData { appendEndpointFile("file", file("a.txt")) })
        }

        assertEquals(
            listOf("""Content-Disposition: form-data; name="file"; filename="a.txt"; size=5"""),
            bodyOf(content).dispositionLines(),
        )
    }

    @Test
    fun `a file part declares its length rather than falling back to chunked`() = runTest {
        val content = with(fake) {
            MultiPartFormDataContent(formData { appendEndpointFile("file", file("a.txt")) })
        }

        val body = bodyOf(content)
        assertTrue("Content-Length: 5" in body, "expected a per-part Content-Length, body was:\n$body")
    }

    @Test
    fun `a file name that needs quoting is escaped`() = runTest {
        val content = with(fake) {
            MultiPartFormDataContent(formData { appendEndpointFile("file", file("my report.txt", size = null)) })
        }

        assertEquals(
            listOf("""Content-Disposition: form-data; name="file"; filename="my report.txt""""),
            bodyOf(content).dispositionLines(),
        )
    }

    @Test
    fun `an item part carries only the form-data disposition plus its Content-Type`() = runTest {
        val content = with(fake) {
            MultiPartFormDataContent(
                formData { appendEndpointItem("from", "me", Stringer.Builtins.StringStringer) }
            )
        }

        val body = bodyOf(content)
        assertEquals(listOf("""Content-Disposition: form-data; name="from""""), body.dispositionLines())
        assertTrue("Content-Type: text/plain" in body, "body was:\n$body")
    }
}
