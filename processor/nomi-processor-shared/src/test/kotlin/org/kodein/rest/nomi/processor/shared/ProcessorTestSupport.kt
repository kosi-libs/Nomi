@file:OptIn(ExperimentalCompilerApi::class)

package org.kodein.rest.nomi.processor.shared

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import com.tschuchort.compiletesting.symbolProcessorProviders
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

internal fun compile(vararg sources: SourceFile): JvmCompilationResult =
    KotlinCompilation().apply {
        this.sources = sources.toList()
        inheritClassPath = true
        configureKsp { symbolProcessorProviders += NomiSharedProcessorProvider() }
    }.compile()

internal fun compileWith(previous: JvmCompilationResult, vararg sources: SourceFile): JvmCompilationResult =
    KotlinCompilation().apply {
        this.sources = sources.toList()
        inheritClassPath = true
        classpaths += previous.outputDirectory
        configureKsp { symbolProcessorProviders += NomiSharedProcessorProvider() }
    }.compile()

internal fun JvmCompilationResult.errors(): List<String> =
    diagnosticMessages.filter { it.severity == com.tschuchort.compiletesting.DiagnosticSeverity.ERROR }.map { it.message }

internal fun JvmCompilationResult.generatedFiles(): List<java.io.File> =
    sourcesGeneratedBySymbolProcessor.toList()

internal fun JvmCompilationResult.generated(name: String): String =
    generatedFiles().single { it.name == name }.readText()
