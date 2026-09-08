package org.kodein.rest.nomi.processor.server

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.general.GeneralChecks
import org.kodein.rest.nomi.processor.common.ksp.annotationNamed
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.isNomiResource
import org.kodein.rest.nomi.processor.common.ksp.isNomiService
import org.kodein.rest.nomi.processor.common.ksp.stringArg
import org.kodein.rest.nomi.processor.common.path.IDENTIFIER_REGEX

// SPECS.adoc: "routeName must be non-blank, match [A-Za-z_][A-Za-z0-9_]*, and must not be a Kotlin
// hard keyword." Soft keywords (`by`, `get`, `set`, ...) are fine as identifiers, so only the hard
// set. Mirrors ClientFactoryChecks' own copy - there's no shared home for it, and each processor
// validates only the one annotation it reads (see processor/SPECS.adoc).
private val HARD_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "val", "var", "when", "while",
)

/**
 * SPECS.adoc § Server implementations (`@ServerEndpointService`) - the rules specific to the server
 * processor. Everything about the `@Endpoint.Service` interfaces themselves lives in
 * `nomi-processor-common`'s `ServiceAnalyzer`, run separately on every service this class
 * implements (see [check]'s return value) - the server processor re-validates them itself rather
 * than assuming the shared processor, or any other processor, already did.
 */
public object ServerServiceChecks {

    /**
     * Reports every violation found and returns the validated `routeName` alongside the "maximal"
     * `@Endpoint.Service` interfaces [declaration] implements - direct or transitive, with any
     * service already reachable through another one in the result filtered out, so
     * [org.kodein.rest.nomi.processor.common.service.ServiceAnalyzer] (which itself walks a
     * service's own ancestor chain) is never asked to double-count endpoints. Returns `null` for
     * an invalid class.
     */
    public fun check(declaration: KSClassDeclaration, reporter: Reporter): ServerServiceCheckResult? {
        var ok = GeneralChecks.checkReadableDeclaration(declaration, reporter)
        val name = declaration.fq()

        if (declaration.classKind != ClassKind.CLASS && declaration.classKind != ClassKind.OBJECT) {
            reporter.error(declaration, NomiServerErrors.notConcreteClassOrObject(name, declaration.classKind.name.lowercase()))
            ok = false
        } else {
            if (Modifier.ABSTRACT in declaration.modifiers) {
                reporter.error(declaration, NomiServerErrors.isAbstract(name)); ok = false
            }
            if (Modifier.SEALED in declaration.modifiers) {
                reporter.error(declaration, NomiServerErrors.isSealed(name)); ok = false
            }
        }

        ok = checkPlacement(declaration, name, reporter) && ok

        val services = implementedServices(declaration)
        if (services.isEmpty()) {
            reporter.error(declaration, NomiServerErrors.noServiceImplemented(name))
            ok = false
        }

        val routeName = checkRouteName(declaration, name, reporter)
        if (routeName == null) ok = false

        return if (ok) ServerServiceCheckResult(routeName!!, services) else null
    }

    // Mirrors ServiceChecks' own placement rule: never inside a resource; every plain enclosing
    // declaration must itself be readable (public/internal, non-local, ...).
    private fun checkPlacement(declaration: KSClassDeclaration, name: String, reporter: Reporter): Boolean {
        var ok = true
        var current = declaration.parentDeclaration
        while (current is KSClassDeclaration) {
            if (current.isNomiResource()) {
                reporter.error(declaration, NomiServerErrors.nestedInResource(name, current.fq()))
                ok = false
            }
            if (!GeneralChecks.checkReadableDeclaration(current, reporter)) ok = false
            current = current.parentDeclaration
        }
        return ok
    }

    // SPECS.adoc § Server implementations: routeName's shape rules, mirroring
    // @ClientEndpointServiceFactory.name's identical ones (ClientFactoryChecks.checkRegistration).
    private fun checkRouteName(declaration: KSClassDeclaration, name: String, reporter: Reporter): String? {
        val annotation = declaration.annotationNamed(SERVER_ENDPOINT_SERVICE_FQ) ?: return null
        val routeName = annotation.stringArg("routeName")
        if (routeName == null || routeName.isBlank() || !IDENTIFIER_REGEX.matches(routeName)) {
            reporter.error(annotation, NomiServerErrors.routeNameBlankOrInvalid(name, routeName ?: "?"))
            return null
        }
        if (routeName in HARD_KEYWORDS) {
            reporter.error(annotation, NomiServerErrors.routeNameIsHardKeyword(name, routeName))
            return null
        }
        return routeName
    }

    // The full supertype closure, filtered to @Endpoint.Service interfaces, then filtered again to
    // only those NOT already an ancestor of another one in that set - ServiceAnalyzer.analyze()
    // walks a service's own @Endpoint.Service ancestors itself, so passing both a service and its
    // own ancestor here would double-count the ancestor's endpoints.
    private fun implementedServices(declaration: KSClassDeclaration): List<KSClassDeclaration> {
        val allServices = declaration.getAllSuperTypes()
            .mapNotNull { it.declaration as? KSClassDeclaration }
            .filter { it.isNomiService() }
            .distinctBy { it.fq() }
            .toList()
        return allServices.filter { candidate ->
            allServices.none { other ->
                other.fq() != candidate.fq() &&
                    other.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == candidate.qualifiedName?.asString() }
            }
        }
    }
}
