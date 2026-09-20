package org.kodein.rest.nomi.demo.shared

import io.ktor.http.*
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.Endpoint
import org.kodein.rest.nomi.Endpoint.MultipartFormData.PartPresence
import org.kodein.rest.nomi.EndpointFile
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.EndpointService.Multipart
import org.kodein.rest.nomi.NoFail
import org.kodein.rest.nomi.Stringer
import kotlin.uuid.Uuid


@UseSharedStringers
interface MPService : EndpointService {

    suspend fun Tools.post(
        parts: Multipart<ToolsPostData>
    ): Either<Unit, NoFail>

}

object ArticleContentJsonStringer : Stringer<ArticleContent> {
    override fun encode(value: ArticleContent): String = SharedJson.encodeToString(ArticleContent.serializer(), value)
    override fun decode(encoded: String): Either<ArticleContent, Stringer.Failure> =
        try { Either.value(SharedJson.decodeFromString(ArticleContent.serializer(), encoded)) }
        catch (e: Throwable) { Either.error(Stringer.Failure(e.message ?: "Invalid JSON")) }
    override val contentType: ContentType get() = ContentType.Application.Json
}

@Endpoint.MultipartFormData
sealed interface ToolsPostData {

    data class File(val file: EndpointFile) : ToolsPostData

    @Endpoint.MultipartFormData.Part(presence = PartPresence.Optional)
    data class From(val from: Uuid) : ToolsPostData

    @Endpoint.MultipartFormData.Part(name = "article", presence = PartPresence.RepeatedRequired)
    data class With(
        //@Endpoint.Stringed(ArticleContentJsonStringer::class)
        val content: ArticleContent,
    ) : ToolsPostData

    companion object

}