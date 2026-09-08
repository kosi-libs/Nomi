package org.kodein.rest.nomi.demo.client

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import org.kodein.rest.nomi.client.call
import org.kodein.rest.nomi.client.successOrNull
import org.kodein.rest.nomi.demo.shared.Article
import org.kodein.rest.nomi.demo.shared.ArticleContent
import org.kodein.rest.nomi.demo.shared.Articles
import org.kodein.rest.nomi.demo.shared.Headers
import org.kodein.rest.nomi.demo.shared.New
import org.kodein.rest.nomi.demo.shared.SharedJson
import org.kodein.rest.nomi.demo.shared.Tools


suspend fun main() {

    val client = HttpClient {
        install(ContentNegotiation) {
            json(SharedJson)
        }
        defaultRequest {
            url("http://localhost:8080")
            contentType(ContentType.Application.Json)
        }
    }

    APIServices.articles(client).call { Articles().New().create(ArticleContent(title = "", author = "", content = "")) }.also { println(it) }
    val id = APIServices.articles(client).call { Articles().New().create(ArticleContent(title = "Foo", author = "Me", content = "Bar")) }.also { println(it) }.successOrNull()
    APIServices.articles(client).call { Articles().get(page = 1) }.also { println(it) }
    APIServices.articles(client).call { Articles().get(page = 0) }.also { println(it) }
    if (id != null) {
        APIServices.articles(client).call { Article(id).delete() }.also { println(it) }
    }

    APIServices.tools(client).call({ Tools().Headers().get() }) {
        headers {
            append("X-Nomi-Demo", "Yay!")
        }
    } .also { println(it) }

    client.close()
}
