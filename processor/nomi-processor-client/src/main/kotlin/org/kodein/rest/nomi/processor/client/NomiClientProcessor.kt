package org.kodein.rest.nomi.processor.client

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import org.kodein.rest.nomi.processor.common.LoggingReporter
import org.kodein.rest.nomi.processor.common.general.StrayAnnotationChecks
import org.kodein.rest.nomi.processor.common.service.AnalyzedService
import org.kodein.rest.nomi.processor.common.service.ServiceAnalyzer

/**
 * Generates, for each `@ClientEndpointServiceFactory` object, the private service implementation +
 * public extension function described by SPECS.adoc § Client processor. All validation of the
 * `@Endpoint.Service` interfaces it names lives in `nomi-processor-common`'s `ServiceAnalyzer` -
 * exactly the same facade the server processor uses - and this module's [ClientFactoryChecks] /
 * [ClientRoundChecks] handle only the `@ClientEndpointServiceFactory` object's own rules. The client
 * processor re-validates every declaration it reads itself (SPECS.adoc § Verifications): it never
 * assumes a dependency's shared or server processor already ran.
 */
public class NomiClientProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var deferred: List<KSAnnotated> = emptyList()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // inDepth: a local @ClientEndpointServiceFactory object (itself a violation) must still be
        // found and reported, not silently skipped because it's nested in a function.
        val symbols = resolver.getSymbolsWithAnnotation(CLIENT_ENDPOINT_SERVICE_FACTORY_FQ, inDepth = true).toList()
        val (notYetResolvable, ready) = symbols.partition { !it.validate() }

        val reporter = LoggingReporter(logger)
        StrayAnnotationChecks.check(resolver, reporter)

        val serviceAnalyzer = ServiceAnalyzer(resolver, reporter)

        val declarations = ready.filterIsInstance<KSClassDeclaration>()
            .filter { it.containingFile != null } // only objects declared in this compilation are ours to generate

        val objectRegistrations = declarations.mapNotNull { obj ->
            ClientFactoryChecks.check(obj, reporter)?.let { obj to it }
        }

        val entries = objectRegistrations.flatMap { (obj, regs) -> regs.map { obj to it } }
        ClientRoundChecks(resolver, reporter).check(entries)

        data class Generatable(val obj: KSClassDeclaration, val registration: ClientFactoryRegistration, val service: AnalyzedService)
        val generatables = mutableListOf<Generatable>()
        for ((obj, registration) in entries) {
            val analyzed = serviceAnalyzer.analyze(registration.serviceClass)
            if (analyzed != null) generatables += Generatable(obj, registration, analyzed)
        }

        // Never a partial file: generate nothing this round if any declaration it needed was invalid.
        if (reporter.errorCount == 0) {
            val generator = ClientFileGenerator(codeGenerator)
            for (g in generatables) generator.generate(g.obj, g.registration, g.service)
        }

        deferred = notYetResolvable
        return notYetResolvable
    }

    override fun finish() {
        for (symbol in deferred) {
            logger.error("Nomi could not resolve this declaration", symbol)
        }
    }
}
