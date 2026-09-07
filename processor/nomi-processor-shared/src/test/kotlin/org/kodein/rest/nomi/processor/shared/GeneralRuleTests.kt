@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.shared

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// SPECS.md § General - the subset relevant to resources and Stringers. Not exercised here (see
// comments at each): expect/actual (needs a multiplatform compilation, kctfork is single-module
// JVM); inner/const/lateinit/@JvmField on an interface property (not valid Kotlin in that
// position, so unreachable); a use-site target on @Endpoint.Stringed (its own @Target already
// makes @get:/@field: a compile error before KSP ever sees it).
class GeneralRuleTests {

    @Test
    fun `rejects a private nested resource`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                private interface Users
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("Users") }, result.errors().toString())
    }

    @Test
    fun `rejects a local resource declared in a function`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                fun use() {
                    @Endpoint.Resource
                    interface Local
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("Local") }, result.errors().toString())
    }

    @Test
    fun `rejects a resource with type parameters`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                interface Res<T>
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("Res") }, result.errors().toString())
    }

    @Test
    fun `rejects a public resource whose Stringer is internal`() {
        val result = compile(
            SourceFile.kotlin(
                "stringers.kt",
                """
                package test
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either
                import java.util.UUID

                internal object InternalUuidStringer : Stringer<UUID> {
                    override fun encode(value: UUID) = value.toString()
                    override fun decode(encoded: String) = Either.Companion.value<UUID, Stringer.Failure>(UUID.fromString(encoded))
                }
                """,
            ),
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.Resource
                interface Res {
                    @Endpoint.Stringed(InternalUuidStringer::class)
                    val id: UUID
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("InternalUuidStringer") }, result.errors().toString())
    }
}
