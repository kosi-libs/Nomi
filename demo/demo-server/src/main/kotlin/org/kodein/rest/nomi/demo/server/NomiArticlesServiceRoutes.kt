package org.kodein.rest.nomi.demo.server

import io.ktor.server.request.*
import io.ktor.server.routing.*
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.Stringer.Builtins.IntStringer
import org.kodein.rest.nomi.demo.shared.NomiArticle
import org.kodein.rest.nomi.demo.shared.NomiArticleComments
import org.kodein.rest.nomi.demo.shared.NomiArticles
import org.kodein.rest.nomi.demo.shared.NomiArticlesNew
import org.kodein.rest.nomi.demo.shared.UuidStringer
import org.kodein.rest.nomi.server.decode
import org.kodein.rest.nomi.server.decodeOrNull
import org.kodein.rest.nomi.server.respond


// GENERATED

@OptIn(InternalNomiAPI::class)
fun Route.service(service: ServerArticlesService) {
    route("/articles") {
        get {
            call.respond(service) {
                NomiArticles
                    .get(
                        page = decodeOrNull(IntStringer, call.queryParameters["page"])
                    )
            }
        }
        route("/new") {
            post {
                call.respond(service) {
                    NomiArticlesNew
                        .create(
                            content = call.receive()
                        )
                }
            }
        }
    }
    route("/article/{articleId}") {
        get {
            call.respond(service) {
                NomiArticle(
                    articleId = decode(UuidStringer, call.parameters["articleId"]!!)
                )
                    .get()
            }
        }
        delete {
            call.respond(service) {
                NomiArticle(
                    articleId = decode(UuidStringer, call.parameters["articleId"]!!)
                )
                    .delete()
            }
        }
        route("/comments") {
            get {
                call.respond(service) {
                    NomiArticleComments(
                        articleId = decode(UuidStringer, call.parameters["articleId"]!!)
                    )
                        .get()
                }
            }
            post {
                call.respond(service) {
                    NomiArticleComments(
                        articleId = decode(UuidStringer, call.parameters["articleId"]!!)
                    )
                        .post(
                            comment = call.receive()
                        )
                }
            }
        }
    }
}
