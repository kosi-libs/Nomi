package org.kodein.rest.nomi.processor.common.service

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.ksp.declaredSupertypes
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.isNomiService
import org.kodein.rest.nomi.processor.common.ksp.visibilityOrNull
import org.kodein.rest.nomi.processor.common.resource.ResourceAnalyzer
import org.kodein.rest.nomi.processor.common.multipart.MultipartAnalyzer
import org.kodein.rest.nomi.processor.common.stringer.StringerResolver

/**
 * Analyzes and validates `@Endpoint.Service` interfaces, per SPECS.adoc § Service declarations,
 * § Endpoint functions, § Endpoint parameters and § Endpoint return types. This is the facade
 * `nomi-processor-server` (and later `-client`) call: [analyze] returns `null` for an invalid
 * service, having already reported every violation - never a partially-built [AnalyzedService].
 */
public class ServiceAnalyzer(private val resolver: Resolver, private val reporter: Reporter) {

    private val resourceAnalyzer = ResourceAnalyzer(resolver, reporter)
    private val stringerResolver = StringerResolver(resolver, reporter)
    private val multipartAnalyzer = MultipartAnalyzer(stringerResolver, reporter)

    private val cache = mutableMapOf<String, AnalyzedService?>()
    private val inProgress = mutableSetOf<String>()

    // Guards against re-running ServiceChecks.check (and so re-reporting the same shape errors) for
    // one interface reached as an ancestor from several different services or several analyze() calls.
    private val shapeChecked = mutableSetOf<String>()

    /** Analyzes [declaration], caching by qualified name so a base service shared by several
     * children is only ever analyzed once. */
    public fun analyze(declaration: KSClassDeclaration): AnalyzedService? {
        val key = declaration.qualifiedName?.asString() ?: return analyzeUncached(declaration)
        if (key in cache) return cache[key]
        if (key in inProgress) return null
        inProgress += key
        val result = try {
            analyzeUncached(declaration)
        } finally {
            inProgress -= key
        }
        cache[key] = result
        return result
    }

    private fun analyzeUncached(declaration: KSClassDeclaration): AnalyzedService? {
        val hierarchy = serviceHierarchy(declaration)

        var ok = true
        for (service in hierarchy) {
            if (shapeChecked.add(service.fq()) && !ServiceChecks.check(service, reporter)) ok = false
        }
        if (!ok) return null

        val endpoints = mutableListOf<AnalyzedEndpoint>()
        var endpointsOk = true
        for (service in hierarchy) {
            for (function in service.getDeclaredFunctions()) {
                val endpoint = analyzeEndpoint(function, service)
                if (endpoint == null) endpointsOk = false else endpoints += endpoint
            }
        }
        if (!endpointsOk) return null

        if (!ServiceChecks.checkRouteCollisions(declaration, endpoints, reporter)) return null

        return AnalyzedService(declaration, declaration.visibilityOrNull(), endpoints)
    }

    private fun analyzeEndpoint(function: KSFunctionDeclaration, declaringService: KSClassDeclaration): AnalyzedEndpoint? {
        val (resource, method) = EndpointChecks.check(function, declaringService, resourceAnalyzer, resolver, reporter) ?: return null

        // SPECS.adoc § Stringers: an endpoint's Stringer scope starts at the interface that
        // *declares* it, not the leaf service being analyzed - an endpoint's wire encoding is
        // fixed where it is written.
        val scopes = stringerResolver.scopesOf(serviceHierarchy(declaringService))

        val (payload, queries) = EndpointParamChecks.check(
            function, method, resource, scopes, stringerResolver, multipartAnalyzer,
            serviceHierarchy(declaringService), declaringService, reporter,
        ) ?: return null
        val (valueType, errorType) = ReturnTypeChecks.check(function, declaringService, reporter) ?: return null

        return AnalyzedEndpoint(function, declaringService, resource, method, payload, queries, valueType, errorType)
    }

    /** [root] then every `@Endpoint.Service` interface it extends, transitively - breadth-first,
     * nearest first, each interface visited once even through a diamond. */
    private fun serviceHierarchy(root: KSClassDeclaration): List<KSClassDeclaration> {
        val visited = mutableSetOf(root.fq())
        val order = mutableListOf(root)
        var frontier = listOf(root)
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<KSClassDeclaration>()
            for (current in frontier) {
                for (supertype in current.declaredSupertypes()) {
                    val superDecl = supertype.declaration as? KSClassDeclaration ?: continue
                    if (!superDecl.isNomiService()) continue
                    if (visited.add(superDecl.fq())) next += superDecl
                }
            }
            order += next
            frontier = next
        }
        return order
    }

    public fun analyzeAll(declarations: Iterable<KSClassDeclaration>): List<AnalyzedService> =
        declarations.mapNotNull { analyze(it) }
}
