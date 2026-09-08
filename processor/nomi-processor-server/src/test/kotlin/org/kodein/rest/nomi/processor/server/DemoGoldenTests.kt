@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals

// Mirrors demo/demo-shared/.../endpoints.kt + Stringers.kt, and demo/demo-server's
// ServerArticlesService (minus the actual endpoint bodies, which the server processor never reads).
private val ENDPOINTS_FIXTURE = SourceFile.kotlin(
    "endpoints.kt",
    """
    package org.kodein.rest.nomi.demo.shared

    import org.kodein.rest.nomi.Endpoint
    import org.kodein.rest.nomi.EndpointService
    import org.kodein.rest.nomi.Either
    import org.kodein.rest.nomi.Error
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

    class ArticleCreationFailure : Error
    class ArticleCommentFailure : Error

    @Endpoint.Service
    interface ArticlesService : EndpointService {
        suspend fun Articles.get(@Endpoint.Query page: Int? = null): Either<List<String>?, NoFail>
        @Endpoint(Endpoint.Method.Post)
        suspend fun Articles.New.create(content: String): Either<Uuid, ArticleCreationFailure>
        suspend fun Article.get(): Either<String, NoFail>
        suspend fun Article.delete(): Either<Unit, NoFail>
        suspend fun Article.Comments.get(@Endpoint.Query page: Int? = null): Either<List<String>?, NoFail>
        suspend fun Article.Comments.post(comment: String): Either<Unit, ArticleCommentFailure>
    }
    """,
)

private val STRINGERS_FIXTURE = SourceFile.kotlin(
    "Stringers.kt",
    """
    package org.kodein.rest.nomi.demo.shared

    import org.kodein.rest.nomi.Endpoint
    import org.kodein.rest.nomi.Stringer
    import org.kodein.rest.nomi.Stringer.Failure
    import org.kodein.rest.nomi.Either
    import org.kodein.rest.nomi.Either.Companion.value
    import kotlin.uuid.Uuid

    object UuidStringer : Stringer<Uuid> {
        override fun encode(value: Uuid): String = value.toString()
        override fun decode(encoded: String): Either<Uuid, Failure> =
            Uuid.parseOrNull(encoded)?.let { value(it) } ?: Either.Companion.error(Failure("Invalid UUID"))
    }

    @Endpoint.UseStringer(Uuid::class, UuidStringer::class)
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.CLASS)
    annotation class UseSharedStringers
    """,
)

private val SERVER_FIXTURE = SourceFile.kotlin(
    "ServerArticlesService.kt",
    """
    package org.kodein.rest.nomi.demo.server

    import org.kodein.rest.nomi.demo.shared.Article
    import org.kodein.rest.nomi.demo.shared.Articles
    import org.kodein.rest.nomi.demo.shared.ArticlesService
    import org.kodein.rest.nomi.demo.shared.ArticleCreationFailure
    import org.kodein.rest.nomi.demo.shared.ArticleCommentFailure
    import org.kodein.rest.nomi.server.ServerEndpointService
    import org.kodein.rest.nomi.Either
    import org.kodein.rest.nomi.NoFail
    import kotlin.uuid.Uuid

    @ServerEndpointService("articles")
    class ServerArticlesService : ArticlesService {
        override suspend fun Articles.get(page: Int?): Either<List<String>?, NoFail> = TODO()
        override suspend fun Articles.New.create(content: String): Either<Uuid, ArticleCreationFailure> = TODO()
        override suspend fun Article.get(): Either<String, NoFail> = TODO()
        override suspend fun Article.delete(): Either<Unit, NoFail> = TODO()
        override suspend fun Article.Comments.get(page: Int?): Either<List<String>?, NoFail> = TODO()
        override suspend fun Article.Comments.post(comment: String): Either<Unit, ArticleCommentFailure> = TODO()
    }
    """,
)

class DemoGoldenTests {

    @Test
    fun `generates the demo server routes`() {
        val result = compile(ENDPOINTS_FIXTURE, STRINGERS_FIXTURE, SERVER_FIXTURE)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))

        val expected = """
            // GENERATED - do not edit
            package org.kodein.rest.nomi.demo.server

            import io.ktor.server.request.receive
            import io.ktor.server.routing.Route
            import io.ktor.server.routing.`get`
            import io.ktor.server.routing.delete
            import io.ktor.server.routing.post
            import io.ktor.server.routing.route
            import kotlin.OptIn
            import org.kodein.rest.nomi.InternalNomiAPI
            import org.kodein.rest.nomi.Stringer
            import org.kodein.rest.nomi.demo.shared.NomiArticle
            import org.kodein.rest.nomi.demo.shared.NomiArticleComments
            import org.kodein.rest.nomi.demo.shared.NomiArticles
            import org.kodein.rest.nomi.demo.shared.NomiArticlesNew
            import org.kodein.rest.nomi.demo.shared.UuidStringer
            import org.kodein.rest.nomi.server.decode
            import org.kodein.rest.nomi.server.decodeOrNull
            import org.kodein.rest.nomi.server.respond

            @OptIn(InternalNomiAPI::class)
            public fun Route.articles(service: ServerArticlesService) {
              route("/articles") {
                `get` {
                  call.respond(service) {
                    NomiArticles
                    .`get`(
                      page = decodeOrNull(Stringer.Builtins.IntStringer, call.queryParameters["page"]),
                    )
                  }
                }
                route("/new") {
                  post {
                    call.respond(service) {
                      NomiArticlesNew
                      .create(
                        content = call.receive(),
                      )
                    }
                  }
                }
              }
              route("/article/{articleId}") {
                `get` {
                  call.respond(service) {
                    NomiArticle(
                      articleId = decode(UuidStringer, call.parameters["articleId"]!!),
                    )
                    .`get`()
                  }
                }
                delete {
                  call.respond(service) {
                    NomiArticle(
                      articleId = decode(UuidStringer, call.parameters["articleId"]!!),
                    )
                    .delete()
                  }
                }
                route("/comments") {
                  `get` {
                    call.respond(service) {
                      NomiArticleComments(
                        articleId = decode(UuidStringer, call.parameters["articleId"]!!),
                      )
                      .`get`(
                        page = decodeOrNull(Stringer.Builtins.IntStringer, call.queryParameters["page"]),
                      )
                    }
                  }
                  post {
                    call.respond(service) {
                      NomiArticleComments(
                        articleId = decode(UuidStringer, call.parameters["articleId"]!!),
                      )
                      .post(
                        comment = call.receive(),
                      )
                    }
                  }
                }
              }
            }

        """.trimIndent()

        assertEquals(expected, result.generated("NomiServerArticlesServiceRoutes.kt"))
    }
}
