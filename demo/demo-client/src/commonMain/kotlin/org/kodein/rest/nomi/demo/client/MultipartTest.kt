package org.kodein.rest.nomi.demo.client

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.HttpMethod.Companion.Post
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.EndpointFile
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.NoFail
import org.kodein.rest.nomi.client.ListMultipart
import org.kodein.rest.nomi.client.NomiClientEndpointService
import org.kodein.rest.nomi.client.NomiServiceCaller
import org.kodein.rest.nomi.client.ServiceCaller
import org.kodein.rest.nomi.client.appendEndpointFile
import org.kodein.rest.nomi.client.appendEndpointItem
import org.kodein.rest.nomi.client.call
import org.kodein.rest.nomi.demo.shared.ArticleContent
import org.kodein.rest.nomi.demo.shared.ArticleContentJsonStringer
import org.kodein.rest.nomi.demo.shared.MPService
import org.kodein.rest.nomi.demo.shared.Tools
import org.kodein.rest.nomi.demo.shared.ToolsPostData
import org.kodein.rest.nomi.demo.shared.UuidStringer
import org.kodein.rest.nomi.forEachPart
import kotlin.uuid.Uuid


// Generated

@OptIn(InternalNomiAPI::class)
fun APIServices.multipartToolsPostData(
    file: EndpointFile,
    from: Uuid? = null,
    with: List<ArticleContent>,
): EndpointService.Multipart<ToolsPostData> =
    ListMultipart(
        buildList {
            add(ToolsPostData.File(file))
            if (from != null) {
                add(ToolsPostData.From(from))
            }
            require(with.isNotEmpty()) { "with is required" }
            addAll(with.map { ToolsPostData.With(it) })
        }
    )

@OptIn(InternalNomiAPI::class)
private class TestMPServiceClient(
    public val client: HttpClient,
    override val buildRequest: HttpRequestBuilder.() -> Unit,
) : MPService, NomiClientEndpointService {
    override suspend fun Tools.post(
        parts: EndpointService.Multipart<ToolsPostData>,
    ): Either<Unit, NoFail> = client.call(
        pathSegments = listOf("tools"),
        method = Post,
    ) {
        setBody(
            MultiPartFormDataContent(
                formData {
                    parts.forEachPart { part ->
                        when (part) {
                            is ToolsPostData.File -> appendEndpointFile("file", part.file)
                            is ToolsPostData.From -> appendEndpointItem("from", part.from, UuidStringer)
                            is ToolsPostData.With -> appendEndpointItem("article", part.content, ArticleContentJsonStringer)
                        }
                    }
                }
            )
        )
    }
}

@OptIn(InternalNomiAPI::class)
public fun APIServices.mp(client: HttpClient): ServiceCaller<MPService> = NomiServiceCaller(
    client = client,
    service = ::TestMPServiceClient,
)
