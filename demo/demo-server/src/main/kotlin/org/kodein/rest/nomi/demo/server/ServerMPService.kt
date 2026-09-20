package org.kodein.rest.nomi.demo.server

import kotlinx.io.readString
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.NoFail
import org.kodein.rest.nomi.demo.shared.ArticleContent
import org.kodein.rest.nomi.demo.shared.MPService
import org.kodein.rest.nomi.demo.shared.Tools
import org.kodein.rest.nomi.demo.shared.ToolsPostData
import org.kodein.rest.nomi.forEachPart
import kotlin.uuid.Uuid


class ServerMPService : MPService {

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

}
