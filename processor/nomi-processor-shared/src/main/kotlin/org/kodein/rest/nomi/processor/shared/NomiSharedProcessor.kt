package org.kodein.rest.nomi.processor.shared

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import org.kodein.rest.nomi.processor.common.LoggingReporter
import org.kodein.rest.nomi.processor.common.NomiFq
import org.kodein.rest.nomi.processor.common.general.StrayAnnotationChecks
import org.kodein.rest.nomi.processor.common.resource.ResourceAnalyzer
import org.kodein.rest.nomi.processor.common.resource.RoundChecks

/**
 * Generates the `Nomi<Chain>` implementation + factory function pair described by SPECS.md
 * § Shared processor, for every `@Endpoint.Resource` interface. All validation lives in
 * `nomi-processor-common` - this class only wires it to a real `Resolver`/`KSPLogger`/`CodeGenerator`
 * and decides, per round, whether to generate anything at all.
 */
public class NomiSharedProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var deferred: List<KSAnnotated> = emptyList()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // inDepth: a local @Endpoint.Resource (itself a violation) must still be found and
        // reported, not silently skipped because it's nested in a function.
        val symbols = resolver.getSymbolsWithAnnotation(NomiFq.RESOURCE, inDepth = true).toList()
        val (notYetResolvable, ready) = symbols.partition { !it.validate() }

        val reporter = LoggingReporter(logger)
        StrayAnnotationChecks.check(resolver, reporter)

        val analyzer = ResourceAnalyzer(resolver, reporter)

        val declarations = ready.filterIsInstance<KSClassDeclaration>()
            .filter { it.containingFile != null } // only resources declared in this compilation are ours to generate

        val analyzed = analyzer.analyzeAll(declarations)
        RoundChecks(resolver, reporter).check(analyzed)

        // Never a partial file: generate nothing this round if any resource it needed was invalid.
        if (reporter.errorCount == 0) {
            val generator = ResourceFileGenerator(codeGenerator)
            for ((file, resources) in analyzed.groupBy { it.containingFile }) {
                if (file != null) generator.generate(file, resources.sortedBy { it.declaration.sourceLine() })
            }
        }

        deferred = notYetResolvable
        return notYetResolvable
    }

    override fun finish() {
        for (symbol in deferred) {
            logger.error("Nomi could not resolve this declaration", symbol)
        }
    }

    private fun KSClassDeclaration.sourceLine(): Int = (location as? FileLocation)?.lineNumber ?: 0
}
