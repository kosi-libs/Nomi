package org.kodein.rest.nomi.demo.client

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.HttpMethod.Companion.Delete
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.Stringer.Builtins.IntStringer
import org.kodein.rest.nomi.client.NomiClientEndpointService
import org.kodein.rest.nomi.client.NomiServiceCaller
import org.kodein.rest.nomi.client.ServiceCaller
import org.kodein.rest.nomi.client.appendParameter
import org.kodein.rest.nomi.client.call
import org.kodein.rest.nomi.demo.shared.Article
import org.kodein.rest.nomi.demo.shared.ArticleComment
import org.kodein.rest.nomi.demo.shared.ArticleCommentFailure
import org.kodein.rest.nomi.demo.shared.ArticleContent
import org.kodein.rest.nomi.demo.shared.ArticleCreationFailure
import org.kodein.rest.nomi.demo.shared.ArticleSummary
import org.kodein.rest.nomi.demo.shared.Articles
import org.kodein.rest.nomi.demo.shared.ArticlesService
import org.kodein.rest.nomi.demo.shared.UuidStringer
import org.kodein.rpc.nomi.Either
import org.kodein.rpc.nomi.NoFail
import kotlin.uuid.Uuid


// GENERATED

@OptIn(InternalNomiAPI::class)
private class NomiAPIServicesArticlesServiceClient(
    val client: HttpClient,
    override val buildRequest: HttpRequestBuilder.() -> Unit
) : ArticlesService, NomiClientEndpointService {

    override suspend fun Articles.get(page: Int?): Either<List<ArticleSummary>, NoFail> =
        client.call(
            pathSegments = listOf(
                "articles"
            ),
            method = Get,
        ) {
            appendParameter("page", page, IntStringer)
        }

    override suspend fun Articles.New.create(content: ArticleContent): Either<Uuid, ArticleCreationFailure> =
        client.call(
            pathSegments = listOf(
                "articles",
                "new",
            ),
            method = Post,
        ) {
            setBody(content)
        }

    override suspend fun Article.get(): Either<ArticleContent, NoFail> =
        client.call(
            pathSegments = listOf(
                "article",
                UuidStringer.encode(articleId),
            ),
            method = Get,
        )

    override suspend fun Article.delete(): Either<Unit, NoFail> =
        client.call(
            pathSegments = listOf(
                "article",
                UuidStringer.encode(articleId)
            ),
            method = Delete,
        )

    override suspend fun Article.Comments.get(page: Int?): Either<List<ArticleComment>, NoFail> =
        client.call(
            pathSegments = listOf(
                "article",
                UuidStringer.encode(articleId),
                "comments",
            ),
            method = Get,
        ) {
            appendParameter("page", page, IntStringer)
        }

    override suspend fun Article.Comments.post(comment: ArticleComment): Either<Unit, ArticleCommentFailure> =
        client.call(
            pathSegments = listOf(
                "article",
                UuidStringer.encode(articleId),
                "comments",
            ),
            method = Post,
        ) {
            setBody(comment)
        }

}

@OptIn(InternalNomiAPI::class)
public fun APIServices.articles(client: HttpClient): ServiceCaller<ArticlesService> =
    NomiServiceCaller(
        client = client,
        service = ::NomiAPIServicesArticlesServiceClient,
    )
