package org.kodein.rest.nomi.processor.common.service

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import org.kodein.rest.nomi.processor.common.NomiErrors
import org.kodein.rest.nomi.processor.common.NomiFq
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.general.GeneralChecks
import org.kodein.rest.nomi.processor.common.ksp.ARRAY_FQ_NAMES
import org.kodein.rest.nomi.processor.common.ksp.annotationNamed
import org.kodein.rest.nomi.processor.common.ksp.expandTypealias
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.isNomiMultipartPartType
import org.kodein.rest.nomi.processor.common.multipart.MultipartAnalyzer
import org.kodein.rest.nomi.processor.common.ksp.stringArg
import org.kodein.rest.nomi.processor.common.path.IDENTIFIER_REGEX
import org.kodein.rest.nomi.processor.common.resource.AnalyzedResource
import org.kodein.rest.nomi.processor.common.stringer.Registration
import org.kodein.rest.nomi.processor.common.stringer.StringerResolver

// The effective query parameter name allows '.' and '-' in addition to IDENTIFIER_REGEX's charset -
// SPECS.adoc § Endpoint parameters.
private val QUERY_NAME_REGEX = Regex("[A-Za-z_][A-Za-z0-9_.-]*")

/** SPECS.adoc § Endpoint parameters. */
public object EndpointParamChecks {

    /** Reports every violation found (never stops at the first) and returns the endpoint's body and
     * query parameters, or `null` for an invalid parameter list. */
    public fun check(
        function: KSFunctionDeclaration,
        method: HttpMethod,
        resource: AnalyzedResource,
        scopes: List<List<Registration<String, KSClassDeclaration>>>,
        stringerResolver: StringerResolver,
        multipartAnalyzer: MultipartAnalyzer,
        serviceScopeChain: List<KSClassDeclaration>,
        user: KSDeclaration,
        reporter: Reporter,
    ): Pair<RequestPayload?, List<QueryParam>>? {
        val endpointName = function.simpleName.asString()
        var ok = true
        var payload: RequestPayload? = null
        val queries = mutableListOf<QueryParam>()

        for ((index, param) in function.parameters.withIndex()) {
            val paramName = param.name?.asString() ?: "?"
            if (!IDENTIFIER_REGEX.matches(paramName)) {
                reporter.error(param, NomiErrors.endpointParamNameNotPlainIdentifier(endpointName, paramName))
                ok = false
            }

            val queryAnnotation = param.annotationNamed(NomiFq.QUERY)
            if (queryAnnotation == null) {
                if (index != 0) {
                    reporter.error(param, NomiErrors.endpointParamUnannotatedNotFirst(endpointName, paramName))
                    ok = false
                    continue
                }
                val checked = checkPayload(
                    param, paramName, method, multipartAnalyzer, serviceScopeChain, user, endpointName, reporter,
                )
                if (checked == null) ok = false else payload = checked
            } else {
                val checked = checkQuery(param, paramName, queryAnnotation, scopes, stringerResolver, user, endpointName, reporter)
                if (checked == null) ok = false else queries += checked
            }
        }

        if (!checkNoQueryNameCollisions(endpointName, queries, resource, reporter)) ok = false

        return if (ok) payload to queries else null
    }

    /** The one unannotated first parameter is either a `Multipart<T>` or an ordinary body; the two
     * are mutually exclusive by construction, since there is only ever one such parameter. */
    private fun checkPayload(
        param: KSValueParameter,
        paramName: String,
        method: HttpMethod,
        multipartAnalyzer: MultipartAnalyzer,
        serviceScopeChain: List<KSClassDeclaration>,
        user: KSDeclaration,
        endpointName: String,
        reporter: Reporter,
    ): RequestPayload? {
        val rawType = param.type.resolve()
        if (rawType.isError) return null // deferred to the next round, not a validation error
        val isMultipart = rawType.expandTypealias().declaration.qualifiedName?.asString() == NomiFq.MULTIPART
        return if (isMultipart) {
            checkMultipart(param, paramName, method, multipartAnalyzer, serviceScopeChain, user, endpointName, reporter)
        } else {
            checkBody(param, paramName, method, user, endpointName, reporter)
        }
    }

    private fun checkMultipart(
        param: KSValueParameter,
        paramName: String,
        method: HttpMethod,
        multipartAnalyzer: MultipartAnalyzer,
        serviceScopeChain: List<KSClassDeclaration>,
        user: KSDeclaration,
        endpointName: String,
        reporter: Reporter,
    ): MultipartParam? {
        var ok = true
        // A multipart body is a request body, and obeys every rule one does - SPECS.adoc §
        // Endpoint parameters.
        if (method != HttpMethod.Post && method != HttpMethod.Put && method != HttpMethod.Patch) {
            reporter.error(param, NomiErrors.endpointBodyOnGetOrDelete(endpointName, method.name))
            ok = false
        }
        if (param.isVararg) {
            reporter.error(param, NomiErrors.endpointBodyIsVararg(endpointName))
            ok = false
        }
        if (param.hasDefault) {
            reporter.error(param, NomiErrors.endpointBodyHasDefault(endpointName))
            ok = false
        }
        if (param.annotationNamed(NomiFq.STRINGED) != null) {
            reporter.error(param, NomiErrors.endpointBodyHasStringed(endpointName))
            ok = false
        }

        val type = param.type.resolve().expandTypealias()
        if (type.isMarkedNullable) {
            reporter.error(param, NomiErrors.endpointBodyNullable(endpointName))
            ok = false
        }

        val argument = type.arguments.singleOrNull()?.type?.resolve()
        if (argument == null || argument.isError) {
            reporter.error(param, NomiErrors.endpointMultipartTypeArgumentMissing(endpointName))
            return null
        }
        val partType = argument.expandTypealias().declaration as? KSClassDeclaration
        if (partType == null || !partType.isNomiMultipartPartType()) {
            reporter.error(param, NomiErrors.endpointMultipartNotAPartType(endpointName, argument.declaration.fq()))
            return null
        }
        if (!GeneralChecks.checkAtLeastAsVisible(partType, user, reporter)) ok = false

        val analyzed = multipartAnalyzer.analyze(partType, serviceScopeChain, user) ?: return null

        return if (ok) MultipartParam(param, paramName, analyzed) else null
    }

    private fun checkBody(param: KSValueParameter, paramName: String, method: HttpMethod, user: KSDeclaration, endpointName: String, reporter: Reporter): BodyParam? {
        var ok = true
        if (method != HttpMethod.Post && method != HttpMethod.Put && method != HttpMethod.Patch) {
            reporter.error(param, NomiErrors.endpointBodyOnGetOrDelete(endpointName, method.name)); ok = false
        }
        if (param.isVararg) {
            reporter.error(param, NomiErrors.endpointBodyIsVararg(endpointName)); ok = false
        }
        if (param.hasDefault) {
            reporter.error(param, NomiErrors.endpointBodyHasDefault(endpointName)); ok = false
        }
        if (param.annotationNamed(NomiFq.STRINGED) != null) {
            reporter.error(param, NomiErrors.endpointBodyHasStringed(endpointName)); ok = false
        }

        val rawType = param.type.resolve()
        if (rawType.isError) return null // deferred to the next round, not a validation error

        val type = rawType.expandTypealias()
        if (type.isMarkedNullable) {
            reporter.error(param, NomiErrors.endpointBodyNullable(endpointName)); ok = false
        }

        val fqName = type.declaration.qualifiedName?.asString()
        when {
            type.declaration is KSTypeParameter -> {
                reporter.error(param, NomiErrors.endpointBodyForbiddenType(endpointName, "a type parameter")); ok = false
            }
            type.isFunctionType -> {
                reporter.error(param, NomiErrors.endpointBodyForbiddenType(endpointName, "a functional type")); ok = false
            }
            fqName == "kotlin.Unit" || fqName == "kotlin.Nothing" -> {
                reporter.error(param, NomiErrors.endpointBodyForbiddenType(endpointName, fqName)); ok = false
            }
        }
        if (!GeneralChecks.checkAtLeastAsVisible(type.declaration, user, reporter)) ok = false

        return if (ok) BodyParam(param, paramName, type) else null
    }

    private fun checkQuery(
        param: KSValueParameter,
        paramName: String,
        annotation: KSAnnotation,
        scopes: List<List<Registration<String, KSClassDeclaration>>>,
        stringerResolver: StringerResolver,
        user: KSDeclaration,
        endpointName: String,
        reporter: Reporter,
    ): QueryParam? {
        var ok = true
        if (param.isVararg) {
            reporter.error(param, NomiErrors.endpointQueryIsVararg(endpointName)); ok = false
        }

        val wireName = annotation.stringArg("name")?.takeIf { it.isNotEmpty() } ?: paramName
        if (wireName.isBlank() || !QUERY_NAME_REGEX.matches(wireName)) {
            reporter.error(annotation, NomiErrors.endpointQueryNameInvalid(endpointName, wireName)); ok = false
        }

        val rawType = param.type.resolve()
        if (rawType.isError) return null // deferred to the next round, not a validation error

        val expanded = rawType.expandTypealias()
        val optional = expanded.isMarkedNullable
        if (!optional && param.hasDefault) {
            reporter.error(param, NomiErrors.endpointQueryRequiredHasDefault(endpointName, paramName)); ok = false
        }

        val type = expanded.makeNotNullable()
        val fqName = type.declaration.qualifiedName?.asString()
        val forbidden = when {
            type.declaration is KSTypeParameter -> "a type parameter"
            type.isFunctionType -> "a functional type"
            fqName in ARRAY_FQ_NAMES -> "an array"
            fqName == "kotlin.Unit" || fqName == "kotlin.Nothing" || fqName == "kotlin.Any" -> fqName
            else -> null
        }
        if (forbidden != null) {
            reporter.error(param, NomiErrors.endpointQueryForbiddenType(endpointName, forbidden)); return null
        }
        if (!GeneralChecks.checkAtLeastAsVisible(type.declaration, user, reporter)) ok = false

        val stringed = param.annotationNamed(NomiFq.STRINGED)
        if (type.arguments.isNotEmpty() && stringed == null) {
            // A parameterized type can only be served a Stringer via @Endpoint.Stringed on this
            // specific parameter: @Endpoint.UseStringer(type = ...) can only name a raw class.
            reporter.error(param, NomiErrors.endpointQueryParameterizedNeedsStringed(endpointName, paramName))
            return null
        }
        val stringer = stringerResolver.resolveFor(param, "$endpointName($paramName)", type, stringed, scopes, user) ?: return null

        return if (ok) QueryParam(param, paramName, wireName, type, optional, stringer) else null
    }

    // SPECS.adoc: no two query parameters may resolve to the same effective name, and none may
    // collide with a path parameter along the receiver's resource chain - ktor's `RoutingCall
    // .parameters` merges the two, so such a collision would make the value read at that name
    // ambiguous.
    private fun checkNoQueryNameCollisions(endpointName: String, queries: List<QueryParam>, resource: AnalyzedResource, reporter: Reporter): Boolean {
        var ok = true
        val pathParamNames = resource.allParams.map { it.name }.toSet()
        val seen = mutableSetOf<String>()
        for (query in queries) {
            if (query.wireName in pathParamNames) {
                reporter.error(query.declaration, NomiErrors.endpointQueryNameCollidesWithPathParam(endpointName, query.wireName))
                ok = false
            }
            if (!seen.add(query.wireName)) {
                reporter.error(query.declaration, NomiErrors.endpointQueryNameCollision(endpointName, query.wireName))
                ok = false
            }
        }
        return ok
    }
}
