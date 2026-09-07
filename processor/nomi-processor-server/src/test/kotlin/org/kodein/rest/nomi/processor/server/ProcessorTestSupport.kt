@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.server

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import com.tschuchort.compiletesting.symbolProcessorProviders
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.kodein.rest.nomi.processor.shared.NomiSharedProcessorProvider

// Registers the shared processor alongside the server processor under test: a fixture's
// @Endpoint.Resource interfaces need their Nomi… implementations generated in the very same
// compilation for the server processor's own output to reference (and, in cross-module tests, to
// compile against).
internal fun compile(vararg sources: SourceFile): JvmCompilationResult =
    KotlinCompilation().apply {
        this.sources = sources.toList()
        inheritClassPath = true
        // The generated routes call nomi-runtime-server's inline decode/decodeOrNull/decodeRequired
        // /respond, compiled at this project's jvmToolchain(17) - kctfork's own default JVM target
        // is lower, and the Kotlin compiler refuses to inline JVM-17 bytecode into a lower target.
        jvmTarget = "17"
        configureKsp {
            symbolProcessorProviders += NomiSharedProcessorProvider()
            symbolProcessorProviders += NomiServerProcessorProvider()
        }
    }.compile()

internal fun compileWith(previous: JvmCompilationResult, vararg sources: SourceFile): JvmCompilationResult =
    KotlinCompilation().apply {
        this.sources = sources.toList()
        inheritClassPath = true
        jvmTarget = "17"
        classpaths += previous.outputDirectory
        configureKsp {
            symbolProcessorProviders += NomiSharedProcessorProvider()
            symbolProcessorProviders += NomiServerProcessorProvider()
        }
    }.compile()

internal fun JvmCompilationResult.errors(): List<String> =
    diagnosticMessages.filter { it.severity == com.tschuchort.compiletesting.DiagnosticSeverity.ERROR }.map { it.message }

internal fun JvmCompilationResult.generatedFiles(): List<java.io.File> =
    sourcesGeneratedBySymbolProcessor.toList()

internal fun JvmCompilationResult.generated(name: String): String =
    generatedFiles().single { it.name == name }.readText()
