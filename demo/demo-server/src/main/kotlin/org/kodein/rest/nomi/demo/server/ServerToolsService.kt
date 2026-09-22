package org.kodein.rest.nomi.demo.server

import io.ktor.http.ContentType
import kotlinx.io.Buffer
import kotlinx.io.readString
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.Either.Companion.value
import org.kodein.rest.nomi.EndpointBinary
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.NoFail
import org.kodein.rest.nomi.demo.shared.ArticleContent
import org.kodein.rest.nomi.demo.shared.Tools
import org.kodein.rest.nomi.demo.shared.ToolsPostData
import org.kodein.rest.nomi.demo.shared.ToolsService
import org.kodein.rest.nomi.forEachPart
import org.kodein.rest.nomi.server.ServerEndpointService
import org.kodein.rest.nomi.server.routingCall
import kotlin.collections.forEach
import kotlin.use
import kotlin.uuid.Uuid


@ServerEndpointService("tools", withOpenApiSpecs = true)
class ServerToolsService : ToolsService {

    /** Echoes the request's own headers, as a debugging aid. */
    override suspend fun Tools.Headers.get(): Either<List<Pair<String, List<String>>>, NoFail> {
        val headers = routingCall().request.headers.entries().map { it.key to it.value }.toList()
        return value(headers)
    }

    /**
     * Accepts a multipart upload: one file, an optional sender, and one or more articles.
     *
     * Each part's content type comes from the Stringer that encodes it.
     */
    override suspend fun Tools.post(parts: EndpointService.Multipart<ToolsPostData>): Either<Unit, NoFail> {
        // Collect what the parts carry, then act once the whole body has been read. A part's
        // content - a file above all - is only valid inside the handler, so anything that must
        // outlive the loop is read out here rather than kept as an EndpointFile.
        var file: String? = null
        var from: Uuid? = null
        val articles: MutableList<ArticleContent> = mutableListOf()

        parts.forEachPart { part ->
            // Exhaustive on purpose: a `when` statement over a sealed type is checked by the
            // compiler, so adding a part to ToolsPostData breaks this until it is handled here.
            when (part) {
                is ToolsPostData.File ->
                    file = "${part.file.fileName} (${part.file.contentType}) = ${part.file.source().use { it.readString() }}"
                is ToolsPostData.From -> from = part.from
                is ToolsPostData.With -> articles += part.content
            }
        }

        println("Upload from ${from ?: "(none - the part is optional)"}")
        articles.forEach { article ->
            println("  article: $article")
        }
        println("  file: $file")

        return Either.unit()
    }

    /** Answers a raw body, streamed straight to the response channel. */
    override suspend fun Tools.Download.get(): Either<EndpointBinary, NoFail> {
        // The mirror of `put`: the bytes go straight to the response channel, with no serialization
        // and no ContentNegotiation. The size is known here, so it goes out as a Content-Length -
        // leaving it null is what makes the answer chunked.
        val bytes = "Nomi streams this response.".encodeToByteArray()
        return value(
            EndpointBinary(
                size = bytes.size.toLong(),
                contentType = ContentType.Text.Plain,
                source = { Buffer().apply { write(bytes) } },
            )
        )
    }

    /** Accepts a raw request body, streamed, with no serialization. */
    override suspend fun Tools.put(data: EndpointBinary): Either<Unit, NoFail> {
        // A streamed body is valid only while this endpoint runs, so read it here.
        val content = data.source().use { it.readString() }
        println("Streamed ${data.size ?: "an unknown number of"} bytes of ${data.contentType}: $content")
        return Either.unit()
    }

}
