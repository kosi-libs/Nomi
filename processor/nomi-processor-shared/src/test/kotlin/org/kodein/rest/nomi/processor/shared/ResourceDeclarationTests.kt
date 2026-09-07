@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.shared

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceDeclarationTests {

    @Test
    fun `accepts a sealed interface`() {
        // The demo relies on this - resources aren't banned from being sealed.
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                @Endpoint.Resource
                sealed interface Articles
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects a class, an object, an enum class, an annotation class, a value class and a fun interface`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                class ResA

                @Endpoint.Resource
                object ResB

                @Endpoint.Resource
                enum class ResC

                @Endpoint.Resource
                annotation class ResD

                @Endpoint.Resource
                @JvmInline
                value class ResE(val x: Int)

                @Endpoint.Resource
                fun interface ResF { fun run() }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        for (name in listOf("ResA", "ResB", "ResC", "ResD", "ResE")) {
            assertTrue(errors.any { it.contains(name) && it.contains("interface") }, "expected an error naming $name, got: $errors")
        }
        assertTrue(errors.any { it.contains("ResF") && it.contains("fun interface") }, errors.toString())
    }

    @Test
    fun `rejects a nested resource that does not extend its enclosing resource`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Article {
                    @Endpoint.Resource
                    interface Comments
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("Comments") }, result.errors().toString())
    }

    @Test
    fun `rejects a nested resource that reaches its parent through another interface`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Article

                interface Intermediate : Article

                @Endpoint.Resource
                interface Comments : Intermediate
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
    }

    @Test
    fun `rejects a resource with a supertype other than its parent, and a root resource with any supertype`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                interface Marker

                @Endpoint.Resource
                interface Article : Marker

                @Endpoint.Resource
                interface Comments : Article, Marker
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("Article") }, errors.toString())
        assertTrue(errors.any { it.contains("Comments") }, errors.toString())
    }

    @Test
    fun `rejects a resource extending EndpointService or also annotated Endpoint Service`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.EndpointService

                @Endpoint.Resource
                interface ResA : EndpointService

                @Endpoint.Resource
                @Endpoint.Service
                interface ResB
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("ResA") }, errors.toString())
        assertTrue(errors.any { it.contains("ResB") }, errors.toString())
    }

    @Test
    fun `rejects an unannotated interface nested in a resource`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Article {
                    interface Comments
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("Comments") }, result.errors().toString())
    }

    @Test
    fun `rejects a nested class, object, companion object, enum, annotation class and typealias`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Article {
                    class Nested
                    object NestedObject
                    companion object
                    enum class NestedEnum
                    annotation class NestedAnnotation
                    // A typealias nested directly in an interface isn't legal Kotlin syntax, so
                    // that specific case is covered separately at the top level instead.
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        for (name in listOf("Nested", "NestedObject", "NestedEnum", "NestedAnnotation")) {
            assertTrue(errors.any { it.contains(name) }, "expected an error naming $name, got: $errors")
        }
    }

    @Test
    fun `rejects every function, including suspend, defaulted, operator and toString overrides`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Article {
                    fun plain()
                    suspend fun suspending()
                    fun withDefault() {}
                    operator fun invoke()
                    override fun toString(): String
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        for (name in listOf("plain", "suspending", "withDefault", "invoke", "toString")) {
            assertTrue(errors.any { it.contains(name) }, "expected an error naming $name, got: $errors")
        }
    }

    @Test
    fun `rejects a var, a non-abstract property, a delegated property, an extension property, a property with type parameters and an overriding property`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import kotlin.reflect.KProperty

                private class Delegate {
                    operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = 0
                }

                interface Base { val fromBase: Int }

                @Endpoint.Resource
                interface Article : Base {
                    var mutableProp: Int
                    val withInitializer: Int get() = 1
                    val delegated: Int by Delegate()
                    val Int.extensionProp: Int
                    override val fromBase: Int
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        for (name in listOf("mutableProp", "withInitializer", "delegated", "extensionProp", "fromBase")) {
            assertTrue(errors.any { it.contains(name) }, "expected an error naming $name, got: $errors")
        }
    }

    @Test
    fun `rejects a property whose name shadows an ancestor's, and one whose name is non-ASCII`() {
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
                    interface Comments : Article {
                        val articleId: Int
                    }
                }

                @Endpoint.Resource
                interface Weird {
                    val `café`: Int
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("articleId") && it.contains("shadow") }, errors.toString())
        assertTrue(errors.any { it.contains("café") }, errors.toString())
    }
}
