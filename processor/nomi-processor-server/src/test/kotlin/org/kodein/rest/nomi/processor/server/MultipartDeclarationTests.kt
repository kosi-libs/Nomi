@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPECS.adoc § Multipart part types. The part type is reached through a `Multipart<T>` endpoint
 * parameter, so - like the rest of the `@Endpoint.Service` rule set - it is proven here. */
class MultipartDeclarationTests {

    /** Wraps [partType] in the smallest declaration that actually reaches a part type. */
    private fun compilePartType(partType: String, endpoint: String = "parts: EndpointService.Multipart<Upload>") = compile(
        SourceFile.kotlin(
            "parts.kt",
            """
            package test
            import org.kodein.rest.nomi.Endpoint
            import org.kodein.rest.nomi.Endpoint.MultipartFormData.PartPresence
            import org.kodein.rest.nomi.EndpointFile
            import org.kodein.rest.nomi.EndpointService
            import org.kodein.rest.nomi.Either
            import org.kodein.rest.nomi.NoFail
            import org.kodein.rest.nomi.server.ServerEndpointService

            @Endpoint.Resource
            interface Up

            $partType

            @Endpoint.Service
            interface Svc : EndpointService {
                @Endpoint(Endpoint.Method.Post)
                suspend fun Up.post($endpoint): Either<Unit, NoFail>
            }

            @ServerEndpointService("svc")
            class ServerImpl : Svc {
                override suspend fun Up.post($endpoint): Either<Unit, NoFail> = TODO()
            }
            """,
        ),
    )

    private val validUpload = """
        @Endpoint.MultipartFormData
        sealed interface Upload {
            data class File(val file: EndpointFile) : Upload
        }
    """

    @Test
    fun `accepts a sealed interface of nested single-property data classes`() {
        val result = compilePartType(validUpload)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
    }

    @Test
    fun `rejects a part type that is not an interface`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            sealed class Upload {
                data class File(val file: EndpointFile) : Upload()
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("must be a sealed interface") }, result.errors().toString())
    }

    @Test
    fun `rejects a non-sealed interface`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            interface Upload {
                data class File(val file: EndpointFile) : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.errors().any { it.contains("must be a sealed interface") && it.contains("non-sealed") },
            result.errors().toString(),
        )
    }

    @Test
    fun `rejects a part type with no parts`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            sealed interface Upload
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("at least one part") }, result.errors().toString())
    }

    @Test
    fun `rejects a subtype declared outside the part type`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            sealed interface Upload {
                data class File(val file: EndpointFile) : Upload
            }
            data class Outside(val text: String) : Upload
            """,
        )
        // The outside subtype is simply not enumerated, so the part type itself stays valid - what
        // must not happen is it being silently picked up as a part.
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        assertTrue(
            !result.generated("NomiServerImplRoutes.kt").contains("Outside"),
            result.generated("NomiServerImplRoutes.kt"),
        )
    }

    @Test
    fun `rejects a nested declaration that is not a data class`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            sealed interface Upload {
                class File(val file: EndpointFile) : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("must be a data class") }, result.errors().toString())
    }

    @Test
    fun `rejects a nested object as a part`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            sealed interface Upload {
                data class File(val file: EndpointFile) : Upload
                object Marker : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("must be a data class") }, result.errors().toString())
    }

    @Test
    fun `rejects a part with several properties`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            sealed interface Upload {
                data class File(val file: EndpointFile, val extra: String) : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("exactly one val property") }, result.errors().toString())
    }

    @Test
    fun `rejects a part whose payload is a var`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            sealed interface Upload {
                data class File(var file: EndpointFile) : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("must be a val") }, result.errors().toString())
    }

    @Test
    fun `rejects a part payload with no resolvable Stringer`() {
        val result = compilePartType(
            """
            class NoStringer
            @Endpoint.MultipartFormData
            sealed interface Upload {
                data class Thing(val thing: NoStringer) : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("Stringer") }, result.errors().toString())
    }

    @Test
    fun `rejects two parts resolving to the same name`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            sealed interface Upload {
                data class File(val file: EndpointFile) : Upload
                @Endpoint.MultipartFormData.Part(name = "file")
                data class Other(val other: String) : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("same name 'file'") }, result.errors().toString())
    }

    @Test
    fun `rejects an invalid part name`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            sealed interface Upload {
                @Endpoint.MultipartFormData.Part(name = "not a name")
                data class File(val file: EndpointFile) : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.errors().any { it.contains("must be non-blank and match") }, result.errors().toString())
    }

    @Test
    fun `derives a part name from the subtype simple name, first letter lowercased`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            sealed interface Upload {
                data class CoverImage(val coverImage: EndpointFile) : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        assertTrue(result.generated("NomiServerImplRoutes.kt").contains("\"coverImage\""), result.generated("NomiServerImplRoutes.kt"))
    }

    @Test
    fun `an explicit part name overrides the derived one`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            sealed interface Upload {
                @Endpoint.MultipartFormData.Part(name = "cover")
                data class CoverImage(val coverImage: EndpointFile) : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiServerImplRoutes.kt")
        assertTrue(generated.contains("\"cover\""), generated)
        assertTrue(!generated.contains("\"coverImage\""), generated)
    }

    @Test
    fun `each presence reaches the generated presence map`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            sealed interface Upload {
                data class A(val a: String) : Upload
                @Endpoint.MultipartFormData.Part(presence = PartPresence.Optional)
                data class B(val b: String) : Upload
                @Endpoint.MultipartFormData.Part(presence = PartPresence.RepeatedRequired)
                data class C(val c: String) : Upload
                @Endpoint.MultipartFormData.Part(presence = PartPresence.RepeatedOptional)
                data class D(val d: String) : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiServerImplRoutes.kt")
        // An omitted `presence` argument means Required.
        assertTrue(generated.contains("\"a\" to Endpoint.MultipartFormData.PartPresence.Required"), generated)
        assertTrue(generated.contains("\"b\" to Endpoint.MultipartFormData.PartPresence.Optional"), generated)
        assertTrue(generated.contains("\"c\" to Endpoint.MultipartFormData.PartPresence.RepeatedRequired"), generated)
        assertTrue(generated.contains("\"d\" to Endpoint.MultipartFormData.PartPresence.RepeatedOptional"), generated)
    }

    @Test
    fun `a file part decodes through asEndpointFile and a value part through asEndpointValue`() {
        val result = compilePartType(
            """
            @Endpoint.MultipartFormData
            sealed interface Upload {
                data class File(val file: EndpointFile) : Upload
                data class Note(val note: String) : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiServerImplRoutes.kt")
        assertTrue(generated.contains("it.asEndpointFile(\"file\")"), generated)
        assertTrue(generated.contains("it.asEndpointValue(\"note\", Stringer.Builtins.StringStringer)"), generated)
    }

    @Test
    fun `an unknown part name is rejected at runtime by the generated else branch`() {
        val result = compilePartType(validUpload)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        val generated = result.generated("NomiServerImplRoutes.kt")
        assertTrue(generated.contains("else -> httpError("), generated)
        assertTrue(generated.contains("Unknown part"), generated)
    }

    @Test
    fun `a part payload takes its Stringer from Endpoint Stringed on the constructor val`() {
        // Kotlin binds the annotation to the VALUE PARAMETER, not the property - SPECS.adoc § Gotchas.
        val result = compilePartType(
            """
            class Money(val cents: Int)
            object MoneyStringer : org.kodein.rest.nomi.Stringer<Money> {
                override fun encode(value: Money): String = value.cents.toString()
                override fun decode(encoded: String) =
                    org.kodein.rest.nomi.Either.Companion.value<Money, org.kodein.rest.nomi.Stringer.Failure>(Money(encoded.toInt()))
            }
            @Endpoint.MultipartFormData
            sealed interface Upload {
                data class Price(@Endpoint.Stringed(MoneyStringer::class) val price: Money) : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        assertTrue(result.generated("NomiServerImplRoutes.kt").contains("MoneyStringer"), result.generated("NomiServerImplRoutes.kt"))
    }

    @Test
    fun `a part type may carry Endpoint UseStringer as its own Stringer scope`() {
        val result = compilePartType(
            """
            class Money(val cents: Int)
            object MoneyStringer : org.kodein.rest.nomi.Stringer<Money> {
                override fun encode(value: Money): String = value.cents.toString()
                override fun decode(encoded: String) =
                    org.kodein.rest.nomi.Either.Companion.value<Money, org.kodein.rest.nomi.Stringer.Failure>(Money(encoded.toInt()))
            }
            @Endpoint.MultipartFormData
            @Endpoint.UseStringer(Money::class, MoneyStringer::class)
            sealed interface Upload {
                data class Price(val price: Money) : Upload
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.errors().joinToString("\n"))
        assertTrue(result.generated("NomiServerImplRoutes.kt").contains("MoneyStringer"), result.generated("NomiServerImplRoutes.kt"))
    }
}
