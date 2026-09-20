package org.kodein.rest.nomi.demo.server

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.demo.shared.ArticleContentJsonStringer
import org.kodein.rest.nomi.demo.shared.MPService
import org.kodein.rest.nomi.demo.shared.NomiTools
import org.kodein.rest.nomi.demo.shared.ToolsPostData
import org.kodein.rest.nomi.demo.shared.UuidStringer
import org.kodein.rest.nomi.server.EndpointMultipartReceiver
import org.kodein.rest.nomi.server.asEndpointValue
import org.kodein.rest.nomi.server.httpError
import org.kodein.rest.nomi.server.respond


// Generated

@OptIn(InternalNomiAPI::class)
fun Route.mp(service: MPService) {
    route("/tools") {
        post {
            call.respond(service) {
                val multipart = call.receiveMultipart()
                NomiTools.post(
                    EndpointMultipartReceiver(
                        service = this,
                        multipart = multipart,
                        requiredParts = listOf("file", "from"),
                    ) {
                        when (it.name) {
                            "file" -> ToolsPostData.File(it.asEndpointFile("file"))
                            "from" -> ToolsPostData.From(it.asEndpointValue("from", UuidStringer))
                            "article" -> ToolsPostData.With(it.asEndpointValue("article", ArticleContentJsonStringer))
                            else -> httpError(HttpStatusCode.BadRequest, "Unknown part '${it.name}'")
                        }
                    }
                )
            }
        }
    }
}
