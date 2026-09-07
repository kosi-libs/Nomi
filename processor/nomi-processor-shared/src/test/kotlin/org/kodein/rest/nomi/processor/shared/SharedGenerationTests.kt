@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.shared

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedGenerationTests {

    @Test
    fun `generates a data object and a top-level factory for a parameterless root resource`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Users
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiEndpointsResources.kt")
        assertTrue(generated.contains("public data object NomiUsers : Users"), generated)
        assertTrue(generated.contains("public fun Users(): Users = NomiUsers"), generated)
    }

    @Test
    fun `generates a data class holding the resource's own path parameter`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Article {
                    val articleId: Int
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiEndpointsResources.kt")
        assertTrue(generated.contains("override val articleId: Int"), generated)
        assertTrue(generated.contains(") : Article"), generated)
        assertTrue(generated.contains("public fun Article(articleId: Int): Article = NomiArticle(articleId)"), generated)
    }

    @Test
    fun `names a nested resource's implementation after the enclosing resource chain`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Article {
                    val articleId: Int
                    @Endpoint.Resource
                    interface Comments : Article
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiEndpointsResources.kt")
        // Named after the ENCLOSING chain (Article.Comments -> NomiArticleComments), not the path.
        assertTrue(generated.contains("NomiArticleComments"), generated)
        assertTrue(!generated.contains("NomiArticlesComments"), generated)
    }

    @Test
    fun `passes inherited parameters positionally, parents first, before its own`() {
        // The Users/User/Posts/Post example from SPECS.md's Shared processor section.
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource("u")
                interface Users {
                    @Endpoint.Resource
                    interface User : Users {
                        val userId: Int
                        @Endpoint.Resource
                        interface Posts : User {
                            @Endpoint.Resource("p:{postId}")
                            interface Post : Posts {
                                val postId: Int
                            }
                        }
                    }
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiEndpointsResources.kt")
        assertTrue(
            generated.contains(
                """
                |public data class NomiUsersUserPostsPost(
                |  override val userId: Int,
                |  override val postId: Int,
                |) : Users.User.Posts.Post
                """.trimMargin(),
            ),
            generated,
        )
        assertTrue(
            generated.contains("public fun Users.User.Posts.Post(postId: Int): Users.User.Posts.Post = NomiUsersUserPostsPost(userId, postId)"),
            generated,
        )
    }

    @Test
    fun `reads inherited parameters unqualified off the factory receiver`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Article {
                    val articleId: Int
                    @Endpoint.Resource
                    interface Comments : Article
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiEndpointsResources.kt")
        assertTrue(
            generated.contains("public fun Article.Comments(): Article.Comments = NomiArticleComments(articleId)"),
            generated,
        )
    }

    @Test
    fun `emits a data object when the full parameter set is empty and a data class otherwise`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Articles

                @Endpoint.Resource
                interface Article {
                    val articleId: Int
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiEndpointsResources.kt")
        assertTrue(generated.contains("public data object NomiArticles : Articles"), generated)
        assertTrue(generated.contains("public data class NomiArticle("), generated)
    }

    @Test
    fun `mirrors an internal resource's visibility on the implementation and the factory`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                internal interface Users
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiEndpointsResources.kt")
        assertTrue(generated.contains("internal data object NomiUsers : Users"), generated)
        assertTrue(generated.contains("internal fun Users(): Users = NomiUsers"), generated)
    }

    @Test
    fun `generates one file per source file, named after it`() {
        val result = compile(
            SourceFile.kotlin(
                "a.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                @Endpoint.Resource
                interface Foo
                """,
            ),
            SourceFile.kotlin(
                "b.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                @Endpoint.Resource
                interface Bar
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        assertEquals(setOf("NomiAResources.kt", "NomiBResources.kt"), result.generatedFiles().map { it.name }.toSet())
    }

    @Test
    fun `puts every resource of one source file into that one file, implementations before factories`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                @Endpoint.Resource
                interface Foo
                @Endpoint.Resource
                interface Bar
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiEndpointsResources.kt")
        val lastImplIndex = maxOf(generated.indexOf("NomiFoo :"), generated.indexOf("NomiBar :"))
        val firstFactoryIndex = minOf(generated.indexOf("fun Foo()"), generated.indexOf("fun Bar()"))
        assertTrue(lastImplIndex < firstFactoryIndex, generated)
    }

    @Test
    fun `generates for a root resource nested in a plain object`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                object Api {
                    @Endpoint.Resource
                    interface Users
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiEndpointsResources.kt")
        // Per the naming decision: plain enclosing declarations count too (NomiApiUsers, not NomiUsers).
        assertTrue(generated.contains("NomiApiUsers"), generated)
    }
}
