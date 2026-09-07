package org.kodein.rest.nomi.processor.server

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.general.GeneralChecks
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.isNomiResource
import org.kodein.rest.nomi.processor.common.ksp.isNomiService

/**
 * SPECS.md § Server implementations (`@ServerEndpointService`) - the rules specific to the server
 * processor. Everything about the `@Endpoint.Service` interfaces themselves lives in
 * `nomi-processor-common`'s `ServiceAnalyzer`, run separately on every service this class
 * implements (see [check]'s return value) - the server processor re-validates them itself rather
 * than assuming the shared processor, or any other processor, already did.
 */
public object ServerServiceChecks {

    /**
     * Reports every violation found and returns the "maximal" `@Endpoint.Service` interfaces
     * [declaration] implements - direct or transitive, with any service already reachable through
     * another one in the result filtered out, so [org.kodein.rest.nomi.processor.common.service.ServiceAnalyzer]
     * (which itself walks a service's own ancestor chain) is never asked to double-count endpoints.
     * Returns `null` for an invalid class.
     */
    public fun check(declaration: KSClassDeclaration, reporter: Reporter): List<KSClassDeclaration>? {
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

        return if (ok) services else null
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
