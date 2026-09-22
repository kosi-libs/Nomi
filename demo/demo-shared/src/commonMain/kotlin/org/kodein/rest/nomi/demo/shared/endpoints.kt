package org.kodein.rest.nomi.demo.shared

import org.kodein.rest.nomi.Endpoint
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.NoFail
import kotlin.uuid.Uuid


@Endpoint.Resource
sealed interface Articles {

    @Endpoint.Resource
    sealed interface New : Articles
}

@Endpoint.Resource
@UseSharedStringers
sealed interface Article {
    val articleId: Uuid

    @Endpoint.Resource
    sealed interface Comments : Article
}

@Endpoint.Service
interface ArticlesService : EndpointService {

    /**
     * Lists the articles, newest first.
     *
     * Answers `204` past the last page rather than an empty list, so a caller can tell "no more" from
     * "none match".
     */
    suspend fun Articles.get(@Endpoint.Query page: Int? = null): Either<List<ArticleSummary>?, NoFail>

    /** Creates an article and answers its identifier. */
    @Endpoint(Endpoint.Method.Post)
    suspend fun Articles.New.create(content: ArticleContent): Either<Uuid, ArticleCreationFailure>

    /** Reads one article in full. */
    suspend fun Article.get(): Either<ArticleContent, NoFail>

    /** Deletes one article. Deleting one that is already gone is not an error. */
    suspend fun Article.delete(): Either<Unit, NoFail>

    suspend fun Article.Comments.get(@Endpoint.Query page: Int? = null): Either<List<ArticleComment>?, NoFail>

    suspend fun Article.Comments.post(comment: ArticleComment): Either<Unit, ArticleCommentPostFailure>
}
