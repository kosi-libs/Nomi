@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.shared

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals

// Mirrors demo/demo-shared/.../endpoints.kt, minus the @Endpoint.Service block (this processor
// never reads services) - the resource declarations and the Stringers.kt carrier they use.
private val ENDPOINTS_FIXTURE = SourceFile.kotlin(
    "endpoints.kt",
    """
    package org.kodein.rest.nomi.demo.shared

    import org.kodein.rest.nomi.Endpoint
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

// The semantic content this pins down is identical to the hand-written golden file at
// demo/demo-shared/.../NomiEndpointsResources.kt - the differences are purely KotlinPoet's own
// formatting choices: multi-line constructors, explicit `public` modifiers, an explicit
// `kotlin.OptIn` import, and the file-comment wording (KotlinPoet only supports a comment before
// `package`, never after the imports).
private val EXPECTED_GENERATED = """
    // GENERATED - do not edit
    package org.kodein.rest.nomi.demo.shared

    import kotlin.OptIn
    import kotlin.uuid.Uuid
    import org.kodein.rest.nomi.EndpointService
    import org.kodein.rest.nomi.InternalNomiAPI

    @InternalNomiAPI
    public data object NomiArticles : Articles

    @InternalNomiAPI
    public data object NomiArticlesNew : Articles.New

    @InternalNomiAPI
    public data class NomiArticle(
      override val articleId: Uuid,
    ) : Article

    @InternalNomiAPI
    public data class NomiArticleComments(
      override val articleId: Uuid,
    ) : Article.Comments

    @OptIn(InternalNomiAPI::class)
    context(_: EndpointService)
    public fun Articles(): Articles = NomiArticles

    @OptIn(InternalNomiAPI::class)
    context(_: EndpointService)
    public fun Articles.New(): Articles.New = NomiArticlesNew

    @OptIn(InternalNomiAPI::class)
    context(_: EndpointService)
    public fun Article(articleId: Uuid): Article = NomiArticle(articleId)

    @OptIn(InternalNomiAPI::class)
    context(_: EndpointService)
    public fun Article.Comments(): Article.Comments = NomiArticleComments(articleId)

""".trimIndent()

class DemoGoldenTests {

    @Test
    fun `generates the demo endpoints file`() {
        val result = compile(ENDPOINTS_FIXTURE, STRINGERS_FIXTURE)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        assertEquals(EXPECTED_GENERATED, result.generated("NomiEndpointsResources.kt"))
    }
}
