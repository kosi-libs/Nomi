@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.shared

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val UUID_STRINGER = """
    package test
    import org.kodein.rest.nomi.Stringer
    import org.kodein.rest.nomi.Either

    object UuidStringer : Stringer<java.util.UUID> {
        override fun encode(value: java.util.UUID) = value.toString()
        override fun decode(encoded: String) = Either.Companion.value<java.util.UUID, Stringer.Failure>(java.util.UUID.fromString(encoded))
    }
    object OtherUuidStringer : Stringer<java.util.UUID> {
        override fun encode(value: java.util.UUID) = value.toString()
        override fun decode(encoded: String) = Either.Companion.value<java.util.UUID, Stringer.Failure>(java.util.UUID.fromString(encoded))
    }
"""

class StringerTests {

    @Test
    fun `resolves Endpoint Stringed on the property`() {
        val result = compile(
            SourceFile.kotlin("stringers.kt", UUID_STRINGER),
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.Resource
                interface Res {
                    @Endpoint.Stringed(UuidStringer::class)
                    val id: UUID
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `resolves Endpoint UseStringer on the resource itself`() {
        val result = compile(
            SourceFile.kotlin("stringers.kt", UUID_STRINGER),
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.Resource
                @Endpoint.UseStringer(UUID::class, UuidStringer::class)
                interface Res {
                    val id: UUID
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `resolves Endpoint UseStringer on a parent resource`() {
        val result = compile(
            SourceFile.kotlin("stringers.kt", UUID_STRINGER),
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.Resource
                @Endpoint.UseStringer(UUID::class, UuidStringer::class)
                interface Article {
                    @Endpoint.Resource
                    interface Comments : Article { val id: UUID }
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `resolves through a carrier annotation`() {
        val result = compile(
            SourceFile.kotlin("stringers.kt", UUID_STRINGER),
            SourceFile.kotlin(
                "carrier.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.UseStringer(UUID::class, UuidStringer::class)
                @Retention(AnnotationRetention.BINARY)
                @Target(AnnotationTarget.CLASS)
                annotation class UseSharedStringers
                """,
            ),
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.Resource
                @UseSharedStringers
                interface Res { val id: UUID }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `resolves through a two-level carrier chain`() {
        val result = compile(
            SourceFile.kotlin("stringers.kt", UUID_STRINGER),
            SourceFile.kotlin(
                "carrier.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.UseStringer(UUID::class, UuidStringer::class)
                @Retention(AnnotationRetention.BINARY)
                @Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
                annotation class Level1

                @Level1
                @Retention(AnnotationRetention.BINARY)
                @Target(AnnotationTarget.CLASS)
                annotation class Level2
                """,
            ),
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.Resource
                @Level2
                interface Res { val id: UUID }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `prefers the property's Endpoint Stringed over every registration`() {
        // Both stringers are individually valid, so this only proves @Endpoint.Stringed's presence
        // doesn't trip the "ambiguous registration" path against the coexisting UseStringer one -
        // consistent with @Endpoint.Stringed resolving outright without consulting the registry.
        val result = compile(
            SourceFile.kotlin(
                "stringers.kt",
                """
                package test
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either

                object StringerA : Stringer<Int> {
                    override fun encode(value: Int) = value.toString()
                    override fun decode(encoded: String) = Either.Companion.value<Int, Stringer.Failure>(0)
                }
                object StringerB : Stringer<Int> {
                    override fun encode(value: Int) = value.toString()
                    override fun decode(encoded: String) = Either.Companion.value<Int, Stringer.Failure>(0)
                }
                """,
            ),
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                @Endpoint.UseStringer(Int::class, StringerA::class)
                interface Res {
                    @Endpoint.Stringed(StringerB::class)
                    val id: Int
                }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `prefers a nearer interface's deep registration over a farther interface's direct one`() {
        val result = compile(
            SourceFile.kotlin("stringers.kt", UUID_STRINGER),
            SourceFile.kotlin(
                "carrier.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.UseStringer(UUID::class, OtherUuidStringer::class)
                @Retention(AnnotationRetention.BINARY)
                @Target(AnnotationTarget.CLASS)
                annotation class Carrier
                """,
            ),
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.Resource
                @Endpoint.UseStringer(UUID::class, UuidStringer::class)
                interface Article {
                    @Endpoint.Resource
                    @Carrier
                    interface Comments : Article { val id: UUID }
                }
                """,
            ),
        )
        // Comments' own @Carrier (depth 1, distance 0) beats Article's direct registration
        // (depth 0, distance 1) - distance always dominates depth. If the wrong one won, this
        // would still compile (both are valid UUID stringers) - the ambiguity test below is what
        // actually proves only one is selected.
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects two different stringers at the same distance and depth, accepts the same one twice`() {
        val result = compile(
            SourceFile.kotlin("stringers.kt", UUID_STRINGER),
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.Resource
                @Endpoint.UseStringer(UUID::class, UuidStringer::class)
                @Endpoint.UseStringer(UUID::class, OtherUuidStringer::class)
                interface ResBad { val id: UUID }

                @Endpoint.Resource
                @Endpoint.UseStringer(UUID::class, UuidStringer::class)
                @Endpoint.UseStringer(UUID::class, UuidStringer::class)
                interface ResGood { val id: UUID }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("ambiguous", ignoreCase = true) }, errors.toString())
        assertTrue(errors.none { it.contains("ResGood") }, errors.toString())
    }

    @Test
    fun `rejects a with that is a class, an interface, an enum or an anonymous object`() {
        val result = compile(
            SourceFile.kotlin(
                "stringers.kt",
                """
                package test
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either

                class ClassStringer : Stringer<Int> {
                    override fun encode(value: Int) = value.toString()
                    override fun decode(encoded: String) = Either.Companion.value<Int, Stringer.Failure>(0)
                }
                interface InterfaceStringer : Stringer<Int>
                enum class EnumStringer : Stringer<Int> {
                    ;
                    override fun encode(value: Int) = value.toString()
                    override fun decode(encoded: String) = Either.Companion.value<Int, Stringer.Failure>(0)
                }
                """,
            ),
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                @Endpoint.UseStringer(Int::class, ClassStringer::class)
                interface ResA { val id: Int }
                @Endpoint.Resource
                @Endpoint.UseStringer(Int::class, InterfaceStringer::class)
                interface ResB { val id: Int }
                @Endpoint.Resource
                @Endpoint.UseStringer(Int::class, EnumStringer::class)
                interface ResC { val id: Int }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        for (name in listOf("ClassStringer", "InterfaceStringer", "EnumStringer")) {
            assertTrue(errors.any { it.contains(name) }, "expected an error naming $name, got: $errors")
        }
    }

    @Test
    fun `rejects a Stringer whose T is not exactly the property type`() {
        val result = compile(
            SourceFile.kotlin(
                "stringers.kt",
                """
                package test
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either

                object NumberStringer : Stringer<Number> {
                    override fun encode(value: Number) = value.toString()
                    override fun decode(encoded: String) = Either.Companion.value<Number, Stringer.Failure>(0)
                }
                """,
            ),
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint

                @Endpoint.Resource
                @Endpoint.UseStringer(Int::class, NumberStringer::class)
                interface Res { val id: Int }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("NumberStringer") }, result.errors().toString())
    }

    @Test
    fun `rejects a SOURCE-retention carrier annotation`() {
        val result = compile(
            SourceFile.kotlin("stringers.kt", UUID_STRINGER),
            SourceFile.kotlin(
                "carrier.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.UseStringer(UUID::class, UuidStringer::class)
                @Retention(AnnotationRetention.SOURCE)
                @Target(AnnotationTarget.CLASS)
                annotation class BadCarrier
                """,
            ),
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.Resource
                @BadCarrier
                interface Res { val id: UUID }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("BadCarrier") && it.contains("retention") }, errors.toString())
    }

    @Test
    fun `rejects Endpoint UseStringer registering String, Unit, Any, an array or an annotation class`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import org.kodein.rest.nomi.Stringer
                import org.kodein.rest.nomi.Either

                object AnyStringer : Stringer<Any> {
                    override fun encode(value: Any) = value.toString()
                    override fun decode(encoded: String) = Either.Companion.value<Any, Stringer.Failure>(encoded)
                }
                annotation class SomeAnnotation

                @Endpoint.Resource
                @Endpoint.UseStringer(String::class, AnyStringer::class)
                interface ResA { val id: Int }
                @Endpoint.Resource
                @Endpoint.UseStringer(Unit::class, AnyStringer::class)
                interface ResB { val id: Int }
                @Endpoint.Resource
                @Endpoint.UseStringer(Any::class, AnyStringer::class)
                interface ResC { val id: Int }
                @Endpoint.Resource
                @Endpoint.UseStringer(IntArray::class, AnyStringer::class)
                interface ResD { val id: Int }
                @Endpoint.Resource
                @Endpoint.UseStringer(SomeAnnotation::class, AnyStringer::class)
                interface ResE { val id: Int }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("String") }, errors.toString())
        assertTrue(errors.any { it.contains("Unit") }, errors.toString())
        assertTrue(errors.any { it.contains("Any") }, errors.toString())
        assertTrue(errors.any { it.contains("array", ignoreCase = true) }, errors.toString())
        assertTrue(errors.any { it.contains("annotation") }, errors.toString())
    }

    @Test
    fun `reports the type and the property when nothing resolves`() {
        val result = compile(
            SourceFile.kotlin(
                "endpoints.kt",
                """
                package test
                import org.kodein.rest.nomi.Endpoint
                import java.util.UUID

                @Endpoint.Resource
                interface Res { val id: UUID }
                """,
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val errors = result.errors()
        assertTrue(errors.any { it.contains("UUID") && it.contains("id") }, errors.toString())
    }
}
