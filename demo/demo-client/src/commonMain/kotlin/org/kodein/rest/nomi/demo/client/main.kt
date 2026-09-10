package org.kodein.rest.nomi.demo.client

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.Flow
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.client.CallStatus
import org.kodein.rest.nomi.client.ServiceCaller
import org.kodein.rest.nomi.client.call
import org.kodein.rest.nomi.client.mapFailure
import org.kodein.rest.nomi.client.callChainFlow
import org.kodein.rest.nomi.client.successOrNull
import org.kodein.rest.nomi.demo.shared.Article
import org.kodein.rest.nomi.demo.shared.ArticleComment
import org.kodein.rest.nomi.demo.shared.ArticleCommentPostFailure
import org.kodein.rest.nomi.demo.shared.ArticleContent
import org.kodein.rest.nomi.demo.shared.ArticleCreationFailure
import org.kodein.rest.nomi.demo.shared.Articles
import org.kodein.rest.nomi.demo.shared.ArticlesService
import org.kodein.rest.nomi.demo.shared.Comments
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

    val articles = APIServices.articles(client)

    articles.call { Articles().New().create(ArticleContent(title = "", author = "", content = "")) }.also { println(it) }
    val id = articles.call { Articles().New().create(ArticleContent(title = "Foo", author = "Me", content = "Bar")) }.also { println(it) }.successOrNull()
    articles.call { Articles().get(page = 1) }.also { println(it) }
    articles.call { Articles().get(page = 0) }.also { println(it) }
    if (id != null) {
        articles.call { Article(id).delete() }.also { println(it) }
    }

    APIServices.tools(client).call({ Tools().Headers().get() }) {
        headers {
            append("X-Nomi-Demo", "Yay!")
        }
    } .also { println(it) }

    createArticleAndPostComment(articles)
        .collect {
            println("Flow: $it")
        }

    client.close()
}

sealed interface FailedCAAPC : Error {
    data class FailedArticleCreation(val error: ArticleCreationFailure) : FailedCAAPC
    data class FailedCommentPost(val error: ArticleCommentPostFailure) : FailedCAAPC
}

suspend fun createArticleAndPostComment(articles: ServiceCaller<ArticlesService>): Flow<CallStatus<Unit, FailedCAAPC>> =
    callChainFlow {
        val id = link {
            articles
                .call { Articles().New().create(ArticleContent(title = "Foo", author = "Me", content = "Bar")) }
                .mapFailure { FailedCAAPC.FailedArticleCreation(it) }
        }

        articles
            .call { Article(id.await()).Comments().post(ArticleComment(author = "Me", content = "Hello World!")) }
            .mapFailure { FailedCAAPC.FailedCommentPost(it) }
    }
