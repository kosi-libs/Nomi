package org.kodein.rest.nomi.demo.shared

import org.kodein.rest.nomi.Endpoint
import org.kodein.rest.nomi.EndpointService
import org.kodein.rpc.nomi.Either
import org.kodein.rpc.nomi.NoFail
import kotlin.uuid.Uuid


@Endpoint.Resource
interface Articles {

    @Endpoint.Resource
    interface New : Articles
}

@Endpoint.Resource
@UseSharedStringers
interface Article {
    val articleId: Uuid

    @Endpoint.Resource
    interface Comments : Article
}

@Endpoint.Service
interface ArticlesService : EndpointService {

    suspend fun Articles.get(@Endpoint.Query page: Int? = null): Either<List<ArticleSummary>, NoFail>

    @Endpoint(Endpoint.Method.Post)
    suspend fun Articles.New.create(content: ArticleContent): Either<Uuid, ArticleCreationFailure>

    suspend fun Article.get(): Either<ArticleContent, NoFail>

    suspend fun Article.delete(): Either<Unit, NoFail>

    suspend fun Article.Comments.get(@Endpoint.Query page: Int? = null): Either<List<ArticleComment>, NoFail>

    suspend fun Article.Comments.post(comment: ArticleComment): Either<Unit, ArticleCommentFailure>
}
