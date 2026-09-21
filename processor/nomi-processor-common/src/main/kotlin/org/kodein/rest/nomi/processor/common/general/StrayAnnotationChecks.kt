package org.kodein.rest.nomi.processor.common.general

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import org.kodein.rest.nomi.processor.common.NomiErrors
import org.kodein.rest.nomi.processor.common.NomiFq
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.isNomiMultipartPartType
import org.kodein.rest.nomi.processor.common.ksp.isNomiResource
import org.kodein.rest.nomi.processor.common.ksp.isNomiService

/**
 * SPECS.adoc § General: "Stray annotations are errors." A processor collects every symbol carrying
 * one of the four Nomi annotations that decorate something *else* - `@Endpoint`, `@Endpoint.Query`,
 * `@Endpoint.Stringed`, `@Endpoint.UseStringer` - and rejects any it does not consume in that
 * position. The other four Nomi annotations (`@Endpoint.Resource`, `@Endpoint.Service`,
 * `@ServerEndpointService`, `@ClientEndpointServiceFactory`) need no such sweep: each is already the
 * sole entry point of its own processor's `getSymbolsWithAnnotation` call, so every symbol carrying
 * one is already fully read - there is no "stray" case left for them.
 *
 * Every check here is purely *positional*: does the annotated symbol's immediately enclosing
 * declaration carry the right *kind* of Nomi annotation? It never re-validates that enclosing
 * declaration's own shape - that is `ResourceChecks`/`ServiceChecks`'s job, run only when something
 * actually reads it - matching this module's existing "a registration's own shape is validated at
 * discovery, not at use" principle (`processor/SPECS.adoc`'s Gotchas), applied here to avoid a second
 * code path re-reporting a problem the real analyzers already own.
 *
 * Called once per round by all three processors (shared, server, client) - a real project runs them
 * in different Gradle modules (as this repo's own demo does), so whichever single processor happens
 * to run over a given module must catch this on its own.
 */
public object StrayAnnotationChecks {

    public fun check(resolver: Resolver, reporter: Reporter) {
        checkEndpoint(resolver, reporter)
        checkQuery(resolver, reporter)
        checkStringed(resolver, reporter)
        checkUseStringer(resolver, reporter)
    }

    // @Endpoint targets FUNCTION only - stray unless declared directly inside an @Endpoint.Service.
    private fun checkEndpoint(resolver: Resolver, reporter: Reporter) {
        for (function in resolver.getSymbolsWithAnnotation(NomiFq.ENDPOINT, inDepth = true).filterIsInstance<KSFunctionDeclaration>()) {
            if (!isEndpointFunction(function)) {
                reporter.error(function, NomiErrors.strayEndpointAnnotation(function.fq()))
            }
        }
    }

    // @Endpoint.Query targets VALUE_PARAMETER only - stray unless its own function is one of the
    // above (own or inherited endpoint-shaped functions are indistinguishable at this position, so
    // the same "declared directly inside an @Endpoint.Service" test applies).
    private fun checkQuery(resolver: Resolver, reporter: Reporter) {
        for (param in resolver.getSymbolsWithAnnotation(NomiFq.QUERY, inDepth = true).filterIsInstance<KSValueParameter>()) {
            if (!isEndpointParameter(param)) {
                reporter.error(param, NomiErrors.strayQueryAnnotation(param.name?.asString() ?: "?"))
            }
        }
    }

    // @Endpoint.Stringed targets PROPERTY or VALUE_PARAMETER - a property is stray unless it sits
    // directly inside an @Endpoint.Resource; a parameter is stray unless it's an endpoint parameter,
    // same test as @Endpoint.Query above (this deliberately doesn't also check "not the body
    // parameter" - that narrower rule belongs to EndpointParamChecks.checkBody, for a position this
    // check already accepts as non-stray).
    private fun checkStringed(resolver: Resolver, reporter: Reporter) {
        for (symbol in resolver.getSymbolsWithAnnotation(NomiFq.STRINGED, inDepth = true)) {
            when (symbol) {
                is KSPropertyDeclaration -> {
                    val enclosing = symbol.parentDeclaration as? KSClassDeclaration
                    if (enclosing == null || !(enclosing.isNomiResource() || enclosing.isMultipartPart())) {
                        reporter.error(symbol, NomiErrors.strayStringedAnnotation(symbol.fq()))
                    }
                }
                is KSValueParameter -> {
                    if (!isEndpointParameter(symbol) && !isMultipartPartPayload(symbol)) {
                        reporter.error(symbol, NomiErrors.strayStringedAnnotation(symbol.name?.asString() ?: "?"))
                    }
                }
                else -> Unit // unreachable given @Endpoint.Stringed's own @Target
            }
        }
    }

    // @Endpoint.UseStringer targets CLASS, ANNOTATION_CLASS or FUNCTION - stray unless it lands on
    // a resource, a service, a multipart part type, a carrier annotation class, or an endpoint
    // function. No filterIsInstance here: a symbol of the wrong kind must be reported, not dropped.
    private fun checkUseStringer(resolver: Resolver, reporter: Reporter) {
        for (symbol in resolver.getSymbolsWithAnnotation(NomiFq.USE_STRINGER, inDepth = true)) {
            when (symbol) {
                is KSClassDeclaration -> {
                    // A part type is a legal carrier too: a part payload's Stringer scope chain
                    // passes through it (SPECS.adoc § Multipart part types).
                    val ok = symbol.isNomiResource() || symbol.isNomiService() ||
                        symbol.isNomiMultipartPartType() || symbol.classKind == ClassKind.ANNOTATION_CLASS
                    if (!ok) reporter.error(symbol, NomiErrors.strayUseStringerAnnotation(symbol.fq()))
                }
                is KSFunctionDeclaration -> {
                    // Only an endpoint has a Stringer scope; an override in a @ServerEndpointService
                    // class is not one, and its registrations would never be read.
                    if (!isEndpointFunction(symbol)) {
                        reporter.error(symbol, NomiErrors.strayUseStringerAnnotation(symbol.fq()))
                    }
                }
                else -> Unit // unreachable given @Endpoint.UseStringer's own @Target
            }
        }
    }

    private fun isEndpointFunction(function: KSFunctionDeclaration): Boolean =
        (function.parentDeclaration as? KSClassDeclaration)?.isNomiService() == true

    private fun isEndpointParameter(param: KSValueParameter): Boolean {
        val function = param.parent as? KSFunctionDeclaration ?: return false
        return isEndpointFunction(function)
    }

    /** A part's payload, which carries `@Endpoint.Stringed` the way a query parameter does.
     * On a data class's primary-constructor `val`, Kotlin's applicable-target priority binds the
     * annotation to the VALUE PARAMETER rather than the property - SPECS.adoc § Gotchas - so both
     * positions have to be accepted. */
    private fun isMultipartPartPayload(param: KSValueParameter): Boolean {
        val constructor = param.parent as? KSFunctionDeclaration ?: return false
        val owner = constructor.parentDeclaration as? KSClassDeclaration ?: return false
        return owner.isMultipartPart()
    }

    private fun KSClassDeclaration.isMultipartPart(): Boolean =
        (parentDeclaration as? KSClassDeclaration)?.isNomiMultipartPartType() == true
}
