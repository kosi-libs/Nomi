package org.kodein.rest.nomi.processor.common.resource

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import org.kodein.rest.nomi.processor.common.NomiErrors
import org.kodein.rest.nomi.processor.common.NomiFq
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.general.GeneralChecks
import org.kodein.rest.nomi.processor.common.ksp.annotationNamed
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.isNomiResource
import org.kodein.rest.nomi.processor.common.ksp.stringArg
import org.kodein.rest.nomi.processor.common.ksp.visibilityOrNull
import org.kodein.rest.nomi.processor.common.path.PathResult
import org.kodein.rest.nomi.processor.common.path.resourcePath
import org.kodein.rest.nomi.processor.common.stringer.StringerResolver

/**
 * Analyzes and validates `@Endpoint.Resource` interfaces, per SPECS.adoc § Resource declarations,
 * § Path parameter types, § Resource paths and § Stringers. This is the facade `nomi-processor-shared`
 * (and later `-client`/`-server`) call: [analyze] returns `null` for an invalid resource, having
 * already reported every violation - never a partially-built [AnalyzedResource].
 */
public class ResourceAnalyzer(resolver: Resolver, private val reporter: Reporter) {

    private val stringerResolver = StringerResolver(resolver, reporter)
    private val cache = mutableMapOf<String, AnalyzedResource?>()
    private val inProgress = mutableSetOf<String>()

    /** Analyzes [declaration], caching by qualified name so a resource shared by several children
     * (as their common parent) is only ever analyzed once. */
    public fun analyze(declaration: KSClassDeclaration): AnalyzedResource? {
        val key = declaration.qualifiedName?.asString() ?: return analyzeUncached(declaration)
        if (key in cache) return cache[key]
        if (key in inProgress) return null // a cyclic supertype chain; ResourceChecks reports the shape error itself
        inProgress += key
        val result = try {
            analyzeUncached(declaration)
        } finally {
            inProgress -= key
        }
        cache[key] = result
        return result
    }

    private fun analyzeUncached(declaration: KSClassDeclaration): AnalyzedResource? {
        val enclosingResourceDecl = (declaration.parentDeclaration as? KSClassDeclaration)?.takeIf { it.isNomiResource() }
        val parent = enclosingResourceDecl?.let { analyze(it) }
        if (enclosingResourceDecl != null && parent == null) return null // an invalid parent poisons the child

        if (!ResourceChecks.check(declaration, parent, reporter)) return null

        val enclosing = if (parent == null) plainEnclosingDeclarations(declaration) else emptyList()
        if (parent == null && !checkEnclosingDeclarations(declaration, enclosing)) return null

        val scopeChain = buildScopeChain(declaration, parent, enclosing)
        val scopes = stringerResolver.scopesOf(scopeChain)

        val ownParams = mutableListOf<PathParam>()
        var paramsOk = true
        for (property in declaration.getDeclaredProperties()) {
            val param = PathParamChecks.check(property, scopes, stringerResolver, declaration, reporter)
            if (param == null) paramsOk = false else ownParams += param
        }
        if (!paramsOk) return null

        val pathAnnotation = declaration.annotationNamed(NomiFq.RESOURCE)
        val declaredPath = pathAnnotation?.stringArg("path") ?: ""
        val path = when (val result = resourcePath(declaration.simpleName.asString(), declaredPath, ownParams.map { it.name })) {
            is PathResult.Ok -> result.path
            is PathResult.Failed -> {
                for (error in result.errors) reporter.error(pathAnnotation ?: declaration, NomiErrors.path(error))
                return null
            }
        }

        return AnalyzedResource(
            declaration = declaration,
            parent = parent,
            enclosing = enclosing,
            ownParams = ownParams,
            path = path,
            visibility = declaration.visibilityOrNull(),
        )
    }

    // SPECS.adoc § Resource declarations: a root resource's enclosing declarations must all be
    // "plain" - non-local, at least as visible as itself, and not themselves @Endpoint.Resource.
    private fun checkEnclosingDeclarations(declaration: KSClassDeclaration, enclosing: List<KSClassDeclaration>): Boolean {
        var ok = true
        for (plain in enclosing) {
            if (!GeneralChecks.checkReadableDeclaration(plain, reporter)) ok = false
            if (!GeneralChecks.checkAtLeastAsVisible(plain, declaration, reporter)) ok = false
            if (plain.isNomiResource()) {
                reporter.error(declaration, NomiErrors.resourceEnclosedByResource(declaration.fq(), plain.fq()))
                ok = false
            }
        }
        return ok
    }

    private fun plainEnclosingDeclarations(declaration: KSClassDeclaration): List<KSClassDeclaration> {
        val chain = mutableListOf<KSClassDeclaration>()
        var current = declaration.parentDeclaration
        while (current is KSClassDeclaration) {
            chain += current
            current = current.parentDeclaration
        }
        return chain.asReversed()
    }

    /** Nearest-first: the declaring interface, then its chain of parent resources, then the
     * plain declarations the root of that chain is nested in. See SPECS.adoc § Stringers. */
    private fun buildScopeChain(declaration: KSClassDeclaration, parent: AnalyzedResource?, enclosing: List<KSClassDeclaration>): List<KSClassDeclaration> {
        val resourceAncestorNodes = generateSequence(parent) { it.parent }.toList()
        val rootEnclosing = if (parent == null) enclosing else resourceAncestorNodes.last().enclosing
        return listOf(declaration) + resourceAncestorNodes.map { it.declaration } + rootEnclosing.asReversed()
    }

    public fun analyzeAll(declarations: Iterable<KSClassDeclaration>): List<AnalyzedResource> =
        declarations.mapNotNull { analyze(it) }
}
