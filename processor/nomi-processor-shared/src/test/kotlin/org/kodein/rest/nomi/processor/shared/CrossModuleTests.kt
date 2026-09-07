@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.shared

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// SPECS.md: "each processor verifies every declaration it reads, including ones from a dependency
// module - it never assumes a dependency was already checked". These compile a Stringer object in
// one module, then reference it from a resource in a second module compiled against the first's
// output, exercising the Origin.KOTLIN_LIB path through StringerResolver.
class CrossModuleTests {

    @Test
    fun `validates a Stringer object read from a compiled dependency`() {
        val moduleA = compile(
            SourceFile.kotlin(
                "stringers.kt",
                """
                package moduleA
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either
                import java.util.UUID

                object UuidStringer : Stringer<UUID> {
                    override fun encode(value: UUID) = value.toString()
                    override fun decode(encoded: String) = Either.Companion.value<UUID, Stringer.Failure>(UUID.fromString(encoded))
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, moduleA.exitCode, moduleA.errors().joinToString("\n"))

        val moduleB = compileWith(
            moduleA,
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package moduleB
                import org.kodein.rest.nomi.Endpoint
                import moduleA.UuidStringer
                import java.util.UUID

                @Endpoint.Resource
                @Endpoint.UseStringer(UUID::class, UuidStringer::class)
                interface Res { val id: UUID }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, moduleB.exitCode, moduleB.errors().joinToString("\n"))
    }

    @Test
    fun `rejects an invalid Stringer read from a compiled dependency`() {
        val moduleA = compile(
            SourceFile.kotlin(
                "stringers.kt",
                """
                package moduleA
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either
                import java.util.UUID

                // A class, not an object - invalid regardless of which module reads it.
                class NotAStringer : Stringer<UUID> {
                    override fun encode(value: UUID) = value.toString()
                    override fun decode(encoded: String) = Either.Companion.value<UUID, Stringer.Failure>(UUID.fromString(encoded))
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, moduleA.exitCode, moduleA.errors().joinToString("\n"))

        val moduleB = compileWith(
            moduleA,
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package moduleB
                import org.kodein.rest.nomi.Endpoint
                import moduleA.NotAStringer
                import java.util.UUID

                @Endpoint.Resource
                @Endpoint.UseStringer(UUID::class, NotAStringer::class)
                interface Res { val id: UUID }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, moduleB.exitCode)
        assertTrue(moduleB.errors().any { it.contains("NotAStringer") }, moduleB.errors().toString())
    }
}
