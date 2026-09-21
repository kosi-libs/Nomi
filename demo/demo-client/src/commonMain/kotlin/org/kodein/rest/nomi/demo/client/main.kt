package org.kodein.rest.nomi.demo.client

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.flow.Flow
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.EndpointBinary
import org.kodein.rest.nomi.EndpointFile
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.NoFail
import org.kodein.rest.nomi.client.CallStatus
import org.kodein.rest.nomi.client.ServiceCaller
import org.kodein.rest.nomi.client.call
import org.kodein.rest.nomi.client.callChainFlow
import org.kodein.rest.nomi.client.callStreaming
import org.kodein.rest.nomi.client.mapFailure
import org.kodein.rest.nomi.client.successOr
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
import org.kodein.rest.nomi.demo.shared.ToolsPostData
import org.kodein.rest.nomi.mapValue
import kotlin.uuid.Uuid


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
    val id = articles.call { Articles().New().create(ArticleContent(title = "Foo", author = "Me", content = "Bar")) }.also { println(it) }.successOr { error("?") }
    articles.call { Articles().get(page = 1) }.also { println(it) }
    articles.call { Articles().get(page = 0) }.also { println(it) }

    articles.callStreaming({ Article(id).get() }) { get ->
        get().mapValue { it.content }
    }.also { println(it) }

    articles.call { Article(id).delete() }.also { println(it) }

    APIServices.tools(client).call({ Tools().Headers().get() }) {
        headers {
            append("X-Nomi-Demo", "Yay!")
        }
    } .also { println(it) }

    createArticleAndPostComment(articles)
        .collect {
            println("Flow: $it")
        }

    val source = buildPacket { write("Foo-Bar!".encodeToByteArray()) }

    APIServices.tools(client).call {
        Tools().post(
            APIServices.multipartToolsPostData(
                file = EndpointFile(
                    fileName = "foo.txt",
                    fileSize = 8,
                    source = { source }
                ),
                from = Uuid.random(),
                with = listOf(ArticleContent("Yay", "Salomon", "This is cool!")),
            )
        )
    }.also { println(it) }

    val streamed = buildPacket { write("Streamed!".encodeToByteArray()) }

    APIServices.tools(client).call {
        Tools().put(
            EndpointBinary(
                size = 9,
                contentType = ContentType.Text.Plain,
                source = { streamed },
            )
        )
    }.also { println(it) }

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
