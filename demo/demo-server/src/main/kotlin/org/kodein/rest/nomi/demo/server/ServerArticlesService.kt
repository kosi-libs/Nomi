package org.kodein.rest.nomi.demo.server

import io.ktor.http.*
import org.kodein.rest.nomi.demo.shared.Article
import org.kodein.rest.nomi.demo.shared.ArticleComment
import org.kodein.rest.nomi.demo.shared.ArticleCommentFailure
import org.kodein.rest.nomi.demo.shared.ArticleContent
import org.kodein.rest.nomi.demo.shared.ArticleCreationFailure
import org.kodein.rest.nomi.demo.shared.ArticleSummary
import org.kodein.rest.nomi.demo.shared.Articles
import org.kodein.rest.nomi.demo.shared.ArticlesService
import org.kodein.rest.nomi.server.ServerEndpointService
import org.kodein.rest.nomi.server.httpError
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.Either.Companion.error
import org.kodein.rest.nomi.Either.Companion.unit
import org.kodein.rest.nomi.Either.Companion.value
import org.kodein.rest.nomi.NoFail
import kotlin.uuid.Uuid


@ServerEndpointService("articles")
class ServerArticlesService : ArticlesService {

    private val articles = arrayListOf(
        Triple(
            Uuid.random(),
            ArticleContent(
                title = "Lorem Ipsum",
                author = "Jane Doe",
                content = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Ut vulputate ullamcorper felis, ac posuere orci tempor vel. Suspendisse ut lorem at erat aliquet bibendum. Integer ex nulla, tincidunt sit amet diam nec, aliquet ornare nibh. Mauris aliquet lacus et dui iaculis, vel ultrices turpis dictum. Etiam eget diam non magna congue rhoncus nec eu est. Cras bibendum, quam sit amet commodo condimentum, lacus lorem tempus velit, ac dictum augue ligula a nibh. Nullam risus lacus, efficitur at augue eu, malesuada rutrum arcu. Sed vulputate leo efficitur dui pulvinar, nec bibendum risus porttitor."
            ),
            arrayListOf(
                ArticleComment("John Doe", "First!"),
                ArticleComment("Jane Doe", "Always..."),
            )
        ),
        Triple(
            Uuid.random(),
            ArticleContent(
                title = "Maecenas eu urna",
                author = "John Doe",
                content = "Maecenas eu urna eu est scelerisque vehicula vitae quis felis. Pellentesque ornare orci vel nisi eleifend, at aliquam tortor egestas. Mauris vel dui eget orci consectetur venenatis. Integer quis sem in felis tristique posuere vitae eget dolor. Aenean ac luctus nisi. In iaculis, lacus eget mollis consequat, libero ante venenatis quam, vel hendrerit eros velit eu sapien. Proin varius magna non molestie lobortis. Mauris volutpat aliquet est a mattis. Nullam posuere gravida lorem, id accumsan est ultrices in. Pellentesque sollicitudin dolor velit, eu tincidunt velit mollis at. Praesent in semper mauris. Suspendisse potenti. Maecenas ut leo ac velit ullamcorper dapibus. Phasellus sem mauris, ultricies tristique ipsum vitae, bibendum finibus urna. Curabitur venenatis maximus ipsum, sed mattis mi vestibulum vel."
            ),
            arrayListOf(
                ArticleComment("Jane Doe", "Interesting!"),
            )
        )
    )

    override suspend fun Articles.get(page: Int?): Either<List<ArticleSummary>?, NoFail> {
        if (page != null && page != 0) return value(null)
        return value(articles.map { (id, content) -> ArticleSummary(id, content.title, content.author) })
    }

    override suspend fun Articles.New.create(content: ArticleContent): Either<Uuid, ArticleCreationFailure> {
        if (content.title.isBlank()) return error(ArticleCreationFailure.InvalidTitle)
        if (content.author.isBlank()) return error(ArticleCreationFailure.InvalidAuthor)
        if (content.content.isBlank()) return error(ArticleCreationFailure.InvalidContent)
        val id = Uuid.random()
        articles.add(Triple(id, content, arrayListOf()))
        return value(id)
    }

    private fun getArticle(id: Uuid) =
        articles.firstOrNull { (articleId) -> articleId == id }
            ?: httpError(HttpStatusCode.NotFound)

    override suspend fun Article.get(): Either<ArticleContent, NoFail> {
        val (_, article) = getArticle(articleId)
        return value(article)
    }

    override suspend fun Article.delete(): Either<Unit, NoFail> {
        articles.remove(getArticle(articleId))
        return unit()
    }

    override suspend fun Article.Comments.get(page: Int?): Either<List<ArticleComment>?, NoFail> {
        if (page != null && page != 0) return value(null)
        val (_, _, comments) = getArticle(articleId)
        return value(comments)
    }

    override suspend fun Article.Comments.post(comment: ArticleComment): Either<Unit, ArticleCommentFailure> {
        if (comment.author.isBlank()) return error(ArticleCommentFailure.InvalidAuthor)
        if (comment.content.isBlank()) return error(ArticleCommentFailure.InvalidContent)
        val (_, _, comments) = getArticle(articleId)
        comments.add(comment)
        return unit()
    }
}
