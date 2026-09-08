@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.client

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals

// Mirrors demo/demo-shared/.../endpoints.kt + Stringers.kt, and demo/demo-client's APIServices.kt
// (minus the actual body content types, which the client processor never reads).
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

private val CLIENT_FIXTURE = SourceFile.kotlin(
    "APIServices.kt",
    """
    package org.kodein.rest.nomi.demo.client

    import org.kodein.rest.nomi.client.ClientEndpointServiceFactory
    import org.kodein.rest.nomi.demo.shared.ArticlesService

    @ClientEndpointServiceFactory(ArticlesService::class, "articles")
    object APIServices
    """,
)

class DemoGoldenTests {

    @Test
    fun `generates the demo client`() {
        val result = compile(ENDPOINTS_FIXTURE, STRINGERS_FIXTURE, CLIENT_FIXTURE)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))

        val expected = """
            // GENERATED - do not edit
            package org.kodein.rest.nomi.demo.client

            import io.ktor.client.HttpClient
            import io.ktor.client.request.HttpRequestBuilder
            import io.ktor.client.request.setBody
            import io.ktor.http.HttpMethod.Companion.Delete
            import io.ktor.http.HttpMethod.Companion.Get
            import io.ktor.http.HttpMethod.Companion.Post
            import kotlin.Int
            import kotlin.OptIn
            import kotlin.String
            import kotlin.Unit
            import kotlin.collections.List
            import kotlin.uuid.Uuid
            import org.kodein.rest.nomi.Either
            import org.kodein.rest.nomi.InternalNomiAPI
            import org.kodein.rest.nomi.NoFail
            import org.kodein.rest.nomi.Stringer
            import org.kodein.rest.nomi.client.NomiClientEndpointService
            import org.kodein.rest.nomi.client.NomiServiceCaller
            import org.kodein.rest.nomi.client.ServiceCaller
            import org.kodein.rest.nomi.client.appendParameter
            import org.kodein.rest.nomi.client.call
            import org.kodein.rest.nomi.demo.shared.Article
            import org.kodein.rest.nomi.demo.shared.ArticleCommentFailure
            import org.kodein.rest.nomi.demo.shared.ArticleCreationFailure
            import org.kodein.rest.nomi.demo.shared.Articles
            import org.kodein.rest.nomi.demo.shared.ArticlesService
            import org.kodein.rest.nomi.demo.shared.UuidStringer

            @OptIn(InternalNomiAPI::class)
            private class NomiAPIServicesArticlesServiceClient(
              public val client: HttpClient,
              override val buildRequest: HttpRequestBuilder.() -> Unit,
            ) : ArticlesService,
                NomiClientEndpointService {
              override suspend fun Articles.`get`(page: Int?): Either<List<String>?, NoFail> = client.call(
                pathSegments = listOf(
                  "articles",
                ),
                method = Get,
              ) {
                appendParameter("page", page, Stringer.Builtins.IntStringer)
              }

              override suspend fun Article.`get`(): Either<String, NoFail> = client.call(
                pathSegments = listOf(
                  "article",
                  UuidStringer.encode(articleId),
                ),
                method = Get,
              )

              override suspend fun Article.Comments.`get`(page: Int?): Either<List<String>?, NoFail> = client.call(
                pathSegments = listOf(
                  "article",
                  UuidStringer.encode(articleId),
                  "comments",
                ),
                method = Get,
              ) {
                appendParameter("page", page, Stringer.Builtins.IntStringer)
              }

              override suspend fun Articles.New.create(content: String): Either<Uuid, ArticleCreationFailure> = client.call(
                pathSegments = listOf(
                  "articles",
                  "new",
                ),
                method = Post,
              ) {
                setBody(content)
              }

              override suspend fun Article.delete(): Either<Unit, NoFail> = client.call(
                pathSegments = listOf(
                  "article",
                  UuidStringer.encode(articleId),
                ),
                method = Delete,
              )

              override suspend fun Article.Comments.post(comment: String): Either<Unit, ArticleCommentFailure> = client.call(
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
            public fun APIServices.articles(client: HttpClient): ServiceCaller<ArticlesService> = NomiServiceCaller(
              client = client,
              service = ::NomiAPIServicesArticlesServiceClient,
            )

        """.trimIndent()

        assertEquals(expected, result.generated("NomiAPIServicesArticlesServiceClient.kt"))
    }
}
