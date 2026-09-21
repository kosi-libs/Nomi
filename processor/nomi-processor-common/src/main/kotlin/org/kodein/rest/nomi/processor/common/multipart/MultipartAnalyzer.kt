package org.kodein.rest.nomi.processor.common.multipart

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.stringer.StringerResolver

/**
 * The `@Endpoint.MultipartFormData` analogue of
 * [org.kodein.rest.nomi.processor.common.service.ServiceAnalyzer]: [analyze] returns a validated
 * [AnalyzedMultipart], or `null` having already reported every violation - never a partially-built
 * result.
 *
 * A part type's Stringer scope starts at the part type itself and continues through the service
 * that declares the endpoint (SPECS.adoc § Multipart part types), so the scope chain is the
 * caller's to supply - the same part type reached from two services resolves against two different
 * chains, which is why the cache is keyed by both.
 */
public class MultipartAnalyzer(private val stringerResolver: StringerResolver, private val reporter: Reporter) {

    private val cache = mutableMapOf<String, AnalyzedMultipart?>()

    public fun analyze(
        declaration: KSClassDeclaration,
        serviceScopeChain: List<KSClassDeclaration>,
        user: KSDeclaration,
    ): AnalyzedMultipart? {
        val key = declaration.fq() + "@" + serviceScopeChain.joinToString(",") { it.fq() }
        if (key in cache) return cache[key]

        val scopes = stringerResolver.scopesOf(listOf(declaration) + serviceScopeChain)
        val parts = MultipartChecks.check(declaration, scopes, stringerResolver, user, reporter)
        val analyzed = parts?.let { AnalyzedMultipart(declaration, it) }

        cache[key] = analyzed
        return analyzed
    }
}
