package org.kodein.rest.nomi.processor.common.service

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import org.kodein.rest.nomi.processor.common.NomiErrors
import org.kodein.rest.nomi.processor.common.NomiFq
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.general.GeneralChecks
import org.kodein.rest.nomi.processor.common.ksp.declaredSupertypes
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.isNomiResource
import org.kodein.rest.nomi.processor.common.ksp.isNomiService

/** SPECS.adoc § Service declarations. */
public object ServiceChecks {

    /** Reports every violation found (never stops at the first) and returns whether [declaration]
     * is a clean `@Endpoint.Service` interface. Does not itself validate the endpoint functions it
     * declares - that is [EndpointChecks]'s job, run once per function by the caller. */
    public fun check(declaration: KSClassDeclaration, reporter: Reporter): Boolean {
        var ok = GeneralChecks.checkReadableDeclaration(declaration, reporter)
        val name = declaration.fq()

        if (declaration.classKind != ClassKind.INTERFACE) {
            reporter.error(declaration, NomiErrors.serviceNotAnInterface(name, declaration.classKind.name.lowercase()))
            ok = false
        } else {
            if (Modifier.SEALED in declaration.modifiers) {
                reporter.error(declaration, NomiErrors.serviceIsSealed(name))
                ok = false
            }
            if (Modifier.FUN in declaration.modifiers) {
                reporter.error(declaration, NomiErrors.serviceIsFunInterface(name))
                ok = false
            }
        }

        ok = checkPlacement(declaration, name, reporter) && ok
        ok = checkSupertypes(declaration, name, reporter) && ok

        if (declaration.isNomiResource()) {
            reporter.error(declaration, NomiErrors.serviceAlsoAnnotatedResource(name))
            ok = false
        }

        for (property in declaration.getDeclaredProperties()) {
            reporter.error(property, NomiErrors.serviceHasProperty(name, property.simpleName.asString()))
            ok = false
        }
        for (nested in declaration.declarations.filterIsInstance<KSClassDeclaration>()) {
            reporter.error(nested, NomiErrors.serviceHasNestedDeclaration(name, nested.fq()))
            ok = false
        }
        for (typeAlias in declaration.declarations.filterIsInstance<KSTypeAlias>()) {
            reporter.error(typeAlias, NomiErrors.serviceHasNestedDeclaration(name, typeAlias.fq()))
            ok = false
        }
        for (function in declaration.getDeclaredFunctions()) {
            val overriddenService = function.findOverridee()?.parentDeclaration as? KSClassDeclaration
            if (overriddenService != null && overriddenService.isNomiService()) {
                reporter.error(function, NomiErrors.serviceEndpointOverrides(name, function.simpleName.asString()))
                ok = false
            }
        }

        return ok
    }

    // SPECS.adoc: "top-level, or nested only inside plain declarations — never inside a resource interface".
    private fun checkPlacement(declaration: KSClassDeclaration, name: String, reporter: Reporter): Boolean {
        var ok = true
        var current = declaration.parentDeclaration
        while (current is KSClassDeclaration) {
            if (current.isNomiResource()) {
                reporter.error(declaration, NomiErrors.serviceNestedInResource(name, current.fq()))
                ok = false
            }
            if (!GeneralChecks.checkReadableDeclaration(current, reporter)) ok = false
            current = current.parentDeclaration
        }
        return ok
    }

    private fun checkSupertypes(declaration: KSClassDeclaration, name: String, reporter: Reporter): Boolean {
        var ok = true

        val extendsEndpointService = declaration.getAllSuperTypes()
            .any { it.declaration.qualifiedName?.asString() == NomiFq.ENDPOINT_SERVICE }
        if (!extendsEndpointService) {
            reporter.error(declaration, NomiErrors.serviceDoesNotExtendEndpointService(name))
            ok = false
        }

        for (supertype in declaration.declaredSupertypes()) {
            val superDecl = supertype.declaration as? KSClassDeclaration ?: continue
            if (superDecl.qualifiedName?.asString() == NomiFq.ENDPOINT_SERVICE) continue
            if (superDecl.isNomiService()) continue
            if (superDecl.classKind == ClassKind.INTERFACE && superDecl.declarations.none()) continue
            reporter.error(declaration, NomiErrors.serviceSupertypeNotServiceOrEmpty(name, superDecl.fq()))
            ok = false
        }
        return ok
    }

    /** SPECS.adoc § Service declarations: "No two endpoints of one service — own or inherited — may
     * resolve to the same (path, method) pair." Also reused, unchanged, by `nomi-processor-server`
     * for the analogous § Server implementations rule across every service one
     * `@ServerEndpointService` class implements together. */
    public fun checkRouteCollisions(owner: KSClassDeclaration, endpoints: List<AnalyzedEndpoint>, reporter: Reporter): Boolean {
        var ok = true
        for (group in endpoints.groupBy { routeKey(it.fullPath, it.method) }.values) {
            if (group.size <= 1) continue
            for (i in 1 until group.size) {
                reporter.error(
                    group[i].declaration,
                    NomiErrors.routeCollision(owner.fq(), group[0].name, group[i].name, group[i].fullPath.joinToString("/"), group[i].method.name),
                )
                ok = false
            }
        }
        return ok
    }
}
