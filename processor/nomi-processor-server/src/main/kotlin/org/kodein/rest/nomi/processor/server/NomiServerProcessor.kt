package org.kodein.rest.nomi.processor.server

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import org.kodein.rest.nomi.processor.common.LoggingReporter
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.general.StrayAnnotationChecks
import org.kodein.rest.nomi.processor.common.ksp.visibilityOrNull
import org.kodein.rest.nomi.processor.common.service.AnalyzedEndpoint
import org.kodein.rest.nomi.processor.common.service.ServiceAnalyzer
import org.kodein.rest.nomi.processor.common.service.ServiceChecks

/**
 * Generates the `fun Route.<routeName>(service: <Impl>)` described by SPECS.md § Server processor, for
 * every `@ServerEndpointService` class. All validation lives in `nomi-processor-common`'s
 * `ServiceAnalyzer` (the `@Endpoint.Service` interfaces it implements) and this module's
 * [ServerServiceChecks] (the class itself) - this class only wires them to a real
 * `Resolver`/`KSPLogger`/`CodeGenerator` and decides, per round, whether to generate anything at
 * all. The server processor re-validates every declaration it reads itself (SPECS.md §
 * Verifications): it never assumes a dependency's shared or client processor already ran.
 */
public class NomiServerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var deferred: List<KSAnnotated> = emptyList()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // inDepth: a local @ServerEndpointService class (itself a violation) must still be found
        // and reported, not silently skipped because it's nested in a function.
        val symbols = resolver.getSymbolsWithAnnotation(SERVER_ENDPOINT_SERVICE_FQ, inDepth = true).toList()
        val (notYetResolvable, ready) = symbols.partition { !it.validate() }

        val reporter = LoggingReporter(logger)
        StrayAnnotationChecks.check(resolver, reporter)

        val serviceAnalyzer = ServiceAnalyzer(resolver, reporter)

        val declarations = ready.filterIsInstance<KSClassDeclaration>()
            .filter { it.containingFile != null } // only classes declared in this compilation are ours to generate

        val analyzed = declarations.mapNotNull { analyzeServerService(it, serviceAnalyzer, reporter) }

        // Never a partial file: generate nothing this round if any declaration it needed was invalid.
        if (reporter.errorCount == 0) {
            val generator = RouteFileGenerator(codeGenerator)
            for (server in analyzed) generator.generate(server)
        }

        deferred = notYetResolvable
        return notYetResolvable
    }

    override fun finish() {
        for (symbol in deferred) {
            logger.error("Nomi could not resolve this declaration", symbol)
        }
    }

    private fun analyzeServerService(declaration: KSClassDeclaration, serviceAnalyzer: ServiceAnalyzer, reporter: Reporter): AnalyzedServerService? {
        val result = ServerServiceChecks.check(declaration, reporter) ?: return null

        val endpoints = mutableListOf<AnalyzedEndpoint>()
        var ok = true
        for (service in result.services) {
            val analyzed = serviceAnalyzer.analyze(service)
            if (analyzed == null) ok = false else endpoints += analyzed.endpoints
        }
        if (!ok) return null

        if (!ServiceChecks.checkRouteCollisions(declaration, endpoints, reporter)) return null

        return AnalyzedServerService(declaration, declaration.visibilityOrNull(), result.routeName, endpoints)
    }
}
