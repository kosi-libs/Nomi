@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.shared

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The path-string parsing itself is exhaustively covered by ResourcePathParserTests in
// nomi-processor-common; these only exercise the KSP wiring (annotation reading, error placement).
class ResourcePathTests {

    @Test
    fun `derives the path from the interface name and its own parameters`() {
        // No assertion on the derived path string itself here (it's not exposed to generated
        // output); this only confirms the empty-path case compiles clean end to end.
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                @Endpoint.Resource
                interface Article { val articleId: Int }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects an interface name that is not a usable segment and has no explicit path`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                @Endpoint.Resource
                interface `1Bad`
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("1Bad") }, result.errors().toString())
    }

    @Test
    fun `reports an explicit path's errors on the resource`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                @Endpoint.Resource("/leading")
                interface Res
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("/") }, result.errors().toString())
    }

    @Test
    fun `rejects a placeholder naming an inherited parameter`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                @Endpoint.Resource
                interface Article {
                    val articleId: Int
                    @Endpoint.Resource("comments/{articleId}")
                    interface Comments : Article
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("articleId") }, result.errors().toString())
    }

    @Test
    fun `rejects an own parameter absent from an explicit path`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                @Endpoint.Resource("fixed")
                interface Res { val id: Int }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("id") }, result.errors().toString())
    }
}
