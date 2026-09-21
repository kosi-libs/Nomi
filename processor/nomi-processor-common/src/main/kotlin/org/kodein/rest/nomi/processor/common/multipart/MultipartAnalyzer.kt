package org.kodein.rest.nomi.processor.common.multipart

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.stringer.StringerResolver

/**
 * The `@Endpoint.MultipartFormData` analogue of
 * [org.kodein.rest.nomi.processor.common.service.ServiceAnalyzer]: [analyze] returns a validated
 * [AnalyzedMultipart], or `null` having already reported every violation - never a partially-built
 * result.
 *
 * A part payload's Stringer scope is the endpoint function, then the part type, then the service
 * that declares the endpoint (SPECS.adoc § Multipart part types). The endpoint half is the
 * caller's to supply, so [analyze] takes the endpoint's own chain and slots the part type in
 * behind the function - the same part type reached from two endpoints resolves against two
 * different chains, which is why the cache is keyed by both.
 */
public class MultipartAnalyzer(private val stringerResolver: StringerResolver, private val reporter: Reporter) {

    private val cache = mutableMapOf<String, AnalyzedMultipart?>()

    public fun analyze(
        declaration: KSClassDeclaration,
        endpointScopeChain: List<KSDeclaration>,
        user: KSDeclaration,
    ): AnalyzedMultipart? {
        val key = declaration.fq() + "@" + endpointScopeChain.joinToString(",") { scopeKey(it) }
        if (key in cache) return cache[key]

        // The endpoint function stays nearest; the part type sits behind it, ahead of the service
        // hierarchy that follows.
        val scopeChain = endpointScopeChain.take(1) + declaration + endpointScopeChain.drop(1)
        val scopes = stringerResolver.scopesOf(scopeChain)
        val parts = MultipartChecks.check(declaration, scopes, stringerResolver, user, reporter)
        val analyzed = parts?.let { AnalyzedMultipart(declaration, it) }

        cache[key] = analyzed
        return analyzed
    }

    /** Two overloaded endpoints share a [KSFunctionDeclaration.qualifiedName], and may carry
     * different registrations - so a function's parameter types are part of its identity here. */
    private fun scopeKey(scope: KSDeclaration): String = when (scope) {
        is KSFunctionDeclaration ->
            scope.fq() + scope.parameters.joinToString(",", "(", ")") { it.type.resolve().declaration.fq() }
        else -> scope.fq()
    }
}
