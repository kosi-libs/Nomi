package org.kodein.rest.nomi.server

import io.ktor.http.ContentDisposition
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.readString
import org.kodein.rest.nomi.EndpointFile
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.forEachPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class MultipartFakeService : EndpointService

private fun dispositionOf(name: String, fileName: String? = null): Headers = Headers.build {
    append(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Inline
            .withParameter(ContentDisposition.Parameters.Name, name)
            .let { if (fileName == null) it else it.withParameter(ContentDisposition.Parameters.FileName, fileName) }
            .toString(),
    )
}

/** A part whose `release` is counted, so draining can be asserted on - including double releases. */
private class TrackedPart private constructor(build: (suspend () -> Unit) -> PartData) {
    var releaseCount: Int = 0
        private set

    val released: Boolean get() = releaseCount > 0

    val data: PartData = build { releaseCount++ }

    companion object {
        operator fun invoke(name: String, value: String) = TrackedPart { release ->
            PartData.FormItem(value, {}, dispositionOf(name), release)
        }

        fun file(name: String, fileName: String, content: String) = TrackedPart { release ->
            PartData.FileItem(
                provider = { ByteReadChannel(content.encodeToByteArray()) },
                dispose = {},
                partHeaders = dispositionOf(name, fileName),
                release = release,
            )
        }
    }
}

private class FakeMultiPartData(parts: List<PartData>) : MultiPartData {
    private val iterator = parts.iterator()
    override suspend fun readPart(): PartData? = if (iterator.hasNext()) iterator.next() else null
}

@OptIn(InternalNomiAPI::class)
private fun receiver(
    parts: List<TrackedPart>,
    required: List<String>,
): EndpointService.Multipart<String> = EndpointMultipartReceiver(
    service = MultipartFakeService(),
    multipart = FakeMultiPartData(parts.map { it.data }),
    requiredParts = required,
) { part -> (part as PartData.FormItem).value }

/** A receiver of one file part, decoding it the way a generated route does. */
@OptIn(InternalNomiAPI::class)
private fun fileReceiver(part: TrackedPart): EndpointService.Multipart<EndpointFile> = EndpointMultipartReceiver(
    service = MultipartFakeService(),
    multipart = FakeMultiPartData(listOf(part.data)),
    requiredParts = emptyList(),
) { it.asEndpointFile("file") }

class EndpointMultipartReceiverTests {

    @Test
    fun `reads every part in order`() = runTest {
        val parts = listOf(TrackedPart("file", "F"), TrackedPart("from", "S"))
        val seen = mutableListOf<String>()

        receiver(parts, required = listOf("file", "from")).forEachPart { seen += it }

        assertEquals(listOf("F", "S"), seen)
    }

    @Test
    fun `a missing required part answers 400 once the stream is read to its end`() = runTest {
        val parts = listOf(TrackedPart("file", "F"))

        val ex = assertFailsWith<EndpointStatusCodeException> {
            receiver(parts, required = listOf("file", "from")).forEachPart { }
        }

        assertEquals(HttpStatusCode.BadRequest, ex.code)
        assertEquals("Missing required part 'from'", ex.message)
    }

    @Test
    fun `returning early does not report a missing part, and releases what is left`() = runTest {
        val parts = listOf(TrackedPart("file", "F"), TrackedPart("from", "S"), TrackedPart("article", "A"))

        // The endpoint decides it has seen enough after the first part. 'from' is required and will
        // never be read - that must NOT be reported as a malformed request.
        val first = run {
            receiver(parts, required = listOf("file", "from")).forEachPart { return@run it }
            null
        }

        assertEquals("F", first)
        assertTrue(parts[1].released, "unread part 'from' should have been released")
        assertTrue(parts[2].released, "unread part 'article' should have been released")
    }

    @Test
    fun `a throwing handler releases what is left and does not mask the cause`() = runTest {
        val parts = listOf(TrackedPart("file", "F"), TrackedPart("from", "S"))

        assertFailsWith<IllegalStateException> {
            receiver(parts, required = listOf("file", "from")).forEachPart { error("boom") }
        }

        assertTrue(parts[1].released, "unread part 'from' should have been released")
    }

    @Test
    fun `each part is released once its handler returns`() = runTest {
        val parts = listOf(TrackedPart("file", "F"), TrackedPart("from", "S"))
        // Recorded as the handler runs, so this asserts the part is still live *inside* the handler
        // and released on the way out - not merely that everything is released by the end.
        val releasedDuringHandler = mutableListOf<List<Boolean>>()

        receiver(parts, required = emptyList()).forEachPart {
            releasedDuringHandler += parts.map { part -> part.released }
        }

        assertEquals(
            listOf(listOf(false, false), listOf(true, false)),
            releasedDuringHandler,
            "the part being handled must still be live, and the previous one already released",
        )
        assertTrue(parts.all { it.released }, "every part must be released by the end")
    }

    @Test
    fun `a part is released exactly once when the handler returns early`() = runTest {
        // 'from' and 'article' are released by discardRemaining, 'file' by the handler's finally.
        // Nothing may be released twice.
        val parts = listOf(TrackedPart("file", "F"), TrackedPart("from", "S"), TrackedPart("article", "A"))

        run {
            receiver(parts, required = listOf("file", "from")).forEachPart { return@run }
        }

        assertEquals(listOf(1, 1, 1), parts.map { it.releaseCount })
    }

    @Test
    fun `a file decoded from a part cannot be read once that part is released`() = runTest {
        val part = TrackedPart.file("file", "a.txt", "content")
        var escaped: EndpointFile? = null

        fileReceiver(part).forEachPart { file ->
            // Reading inside the handler is the supported use, and must work.
            assertEquals("content", file.source().use { it.readString() })
            escaped = file
        }

        val ex = assertFailsWith<IllegalStateException> { escaped!!.source() }
        assertTrue(
            "must be consumed inside forEachPart" in (ex.message ?: ""),
            "the message should say what the endpoint did wrong, was: ${ex.message}",
        )
    }
}
