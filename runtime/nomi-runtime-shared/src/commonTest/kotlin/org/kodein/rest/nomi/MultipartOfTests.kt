package org.kodein.rest.nomi

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private sealed interface Upload {
    data class File(val file: EndpointFile) : Upload
    data class From(val from: String) : Upload
}

private fun endpointFile(content: String) = EndpointFile(
    fileName = "a.txt",
    source = { Buffer().apply { write(content.encodeToByteArray()) } },
)

@OptIn(InternalNomiAPI::class)
class MultipartOfTests {

    private suspend fun <T : Any> EndpointService.Multipart<T>.toList(): List<T> =
        mutableListOf<T>().also { out -> forEachPart { out += it } }

    @Test
    fun `yields its parts in order`() = runTest {
        val parts = multipartOf(Upload.From("me"), Upload.From("you")).toList()

        assertEquals(listOf(Upload.From("me"), Upload.From("you")), parts)
    }

    @Test
    fun `keeps a repeated part, once per occurrence`() = runTest {
        // Several files under one part name is an ordinary form, and must survive as-is.
        val a = endpointFile("a")
        val b = endpointFile("b")

        val parts = multipartOf(Upload.File(a), Upload.From("me"), Upload.File(b)).toList()

        assertEquals(listOf(Upload.File(a), Upload.From("me"), Upload.File(b)), parts)
    }

    @Test
    fun `an empty multipart yields nothing`() = runTest {
        assertEquals(emptyList(), multipartOf<Upload>().toList())
    }

    @Test
    fun `the vararg and list overloads agree`() = runTest {
        val listed = multipartOf(listOf(Upload.From("me"), Upload.From("you"))).toList()
        val varargs = multipartOf(Upload.From("me"), Upload.From("you")).toList()

        assertEquals(listed, varargs)
    }

    @Test
    fun `consuming it twice fails instead of quietly sending nothing`() = runTest {
        val parts = multipartOf(Upload.From("me"))
        assertEquals(listOf(Upload.From("me")), parts.toList())

        val ex = assertFailsWith<IllegalStateException> { parts.toList() }
        assertTrue(
            "already been consumed" in (ex.message ?: ""),
            "the message should say the stream is spent, was: ${ex.message}",
        )
    }

    @Test
    fun `a file being sent stays readable after the loop`() = runTest {
        // The mirror of the server side: an outgoing part owns nothing, so nothing is closed and
        // the caller's file is still theirs to use.
        val file = endpointFile("hello")

        multipartOf(Upload.File(file)).toList()

        assertEquals("hello", file.source().use { it.readString() })
    }
}
