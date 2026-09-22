package org.kodein.rest.nomi.processor.server

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.validate
import org.kodein.rest.nomi.processor.common.LoggingReporter
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.general.StrayAnnotationChecks
import org.kodein.rest.nomi.processor.common.ksp.annotationNamed
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.visibilityOrNull
import org.kodein.rest.nomi.processor.common.service.AnalyzedEndpoint
import org.kodein.rest.nomi.processor.common.service.ServiceAnalyzer
import org.kodein.rest.nomi.processor.common.service.ServiceChecks

/**
 * Generates the `fun Route.<routeName>(service: <Impl>)` described by SPECS.adoc § Server processor, for
 * every `@ServerEndpointService` class. All validation lives in `nomi-processor-common`'s
 * `ServiceAnalyzer` (the `@Endpoint.Service` interfaces it implements) and this module's
 * [ServerServiceChecks] (the class itself) - this class only wires them to a real
 * `Resolver`/`KSPLogger`/`CodeGenerator` and decides, per round, whether to generate anything at
 * all. The server processor re-validates every declaration it reads itself (SPECS.adoc §
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

        val overrides = overrideMap(declaration, endpoints)
        if (!checkHideMarkers(declaration, result, overrides, reporter)) return null

        return AnalyzedServerService(
            declaration,
            declaration.visibilityOrNull(),
            result.routeName,
            endpoints,
            result.withOpenApiSpecs,
            overrides,
        )
    }

    /**
     * Each endpoint's interface declaration to the function implementing it.
     *
     * `@ServerEndpointService.HideFromOpenApi` sits on the override, while an [AnalyzedEndpoint] holds
     * the interface's declaration, so the two have to be related. `findOverridee` answers the *closest*
     * overridden declaration, which for an endpoint reached through an intermediate service interface
     * is not yet the one that declared it - hence the walk to the top. `getAllFunctions` rather than
     * the declared ones, so an endpoint implemented by a base class is found too.
     */
    private fun overrideMap(
        impl: KSClassDeclaration,
        endpoints: List<AnalyzedEndpoint>,
    ): Map<KSFunctionDeclaration, KSFunctionDeclaration> {
        val byDeclaration = mutableMapOf<KSFunctionDeclaration, KSFunctionDeclaration>()
        for (function in impl.getAllFunctions()) {
            var root = function
            while (true) root = root.findOverridee() as? KSFunctionDeclaration ?: break
            if (root != function) byDeclaration.putIfAbsent(root, function)
        }
        return endpoints.mapNotNull { endpoint ->
            byDeclaration[endpoint.declaration]?.let { endpoint.declaration to it }
        }.toMap()
    }

    // A marker that reaches no endpoint would silently do nothing, which is the worst way for a
    // documentation switch to fail: the document simply comes out with an endpoint the author meant
    // to hide. Both misuses are reported instead.
    private fun checkHideMarkers(
        impl: KSClassDeclaration,
        result: ServerServiceCheckResult,
        overrides: Map<KSFunctionDeclaration, KSFunctionDeclaration>,
        reporter: Reporter,
    ): Boolean {
        val implementing = overrides.values.toSet()
        var ok = true
        for (function in impl.getDeclaredFunctions()) {
            if (function.annotationNamed(HIDE_FROM_OPEN_API_FQ) == null) continue
            val name = function.simpleName.asString()
            if (function !in implementing) {
                reporter.error(function, NomiServerErrors.hideFromOpenApiOnNonEndpoint(impl.fq(), name))
                ok = false
            } else if (!result.withOpenApiSpecs) {
                reporter.error(function, NomiServerErrors.hideFromOpenApiWithoutSpecs(impl.fq(), name))
                ok = false
            }
        }
        return ok
    }
}
