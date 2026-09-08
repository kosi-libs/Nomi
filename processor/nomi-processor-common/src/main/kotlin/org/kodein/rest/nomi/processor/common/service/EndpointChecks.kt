package org.kodein.rest.nomi.processor.common.service

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import org.kodein.rest.nomi.processor.common.NomiErrors
import org.kodein.rest.nomi.processor.common.NomiFq
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.general.GeneralChecks
import org.kodein.rest.nomi.processor.common.ksp.annotationNamed
import org.kodein.rest.nomi.processor.common.ksp.enumArgName
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.isNomiResource
import org.kodein.rest.nomi.processor.common.path.IDENTIFIER_REGEX
import org.kodein.rest.nomi.processor.common.resource.AnalyzedResource
import org.kodein.rest.nomi.processor.common.resource.ResourceAnalyzer

private val FORBIDDEN_MODIFIERS = listOf(
    Modifier.INLINE to "inline",
    Modifier.INFIX to "infix",
    Modifier.OPERATOR to "operator",
    Modifier.EXTERNAL to "external",
    Modifier.TAILREC to "tailrec",
)

private val JVM_ANNOTATIONS_ON_FUNCTIONS = listOf(
    "kotlin.jvm.JvmName" to "JvmName",
    "kotlin.jvm.JvmStatic" to "JvmStatic",
)

private val METHOD_NAMES: Map<String, HttpMethod> = mapOf(
    "get" to HttpMethod.Get,
    "post" to HttpMethod.Post,
    "put" to HttpMethod.Put,
    "patch" to HttpMethod.Patch,
    "delete" to HttpMethod.Delete,
)

/** SPECS.adoc § Endpoint functions. */
public object EndpointChecks {

    /** Reports every violation found (never stops at the first for the function's own independent
     * shape rules) and returns the endpoint's already-[ResourceAnalyzer]-validated receiver resource
     * and resolved [HttpMethod], or `null` for an invalid endpoint. */
    public fun check(
        function: KSFunctionDeclaration,
        declaringService: KSClassDeclaration,
        resourceAnalyzer: ResourceAnalyzer,
        resolver: Resolver,
        reporter: Reporter,
    ): Pair<AnalyzedResource, HttpMethod>? {
        val name = function.simpleName.asString()
        var ok = true

        if (Modifier.SUSPEND !in function.modifiers) {
            reporter.error(function, NomiErrors.endpointNotSuspend(name)); ok = false
        }
        if (!function.isAbstract) {
            reporter.error(function, NomiErrors.endpointHasDefaultBody(name)); ok = false
        }
        if (function.typeParameters.isNotEmpty()) {
            reporter.error(function, NomiErrors.endpointHasTypeParameters(name)); ok = false
        }
        for ((modifier, label) in FORBIDDEN_MODIFIERS) {
            if (modifier in function.modifiers) {
                reporter.error(function, NomiErrors.endpointForbiddenModifier(name, label)); ok = false
            }
        }
        for ((fqName, label) in JVM_ANNOTATIONS_ON_FUNCTIONS) {
            if (function.annotationNamed(fqName) != null) {
                reporter.error(function, NomiErrors.endpointHasJvmAnnotation(name, label)); ok = false
            }
        }
        if (!IDENTIFIER_REGEX.matches(name)) {
            reporter.error(function, NomiErrors.endpointNameNotPlainIdentifier(name)); ok = false
        }

        val method = resolveMethod(function, name, reporter)
        if (method == null) ok = false

        val receiver = checkReceiver(function, name, reporter) ?: return null
        if (!ok) return null

        val analyzedReceiver = resourceAnalyzer.analyze(receiver) ?: return null
        if (!checkReceiverImplAvailable(receiver, analyzedReceiver, resolver, name, reporter)) return null
        if (!GeneralChecks.checkAtLeastAsVisible(analyzedReceiver.declaration, declaringService, reporter)) return null

        return analyzedReceiver to method!!
    }

    private fun resolveMethod(function: KSFunctionDeclaration, name: String, reporter: Reporter): HttpMethod? {
        val endpointAnnotation = function.annotationNamed(NomiFq.ENDPOINT)
        if (endpointAnnotation != null) {
            val methodName = endpointAnnotation.enumArgName("method")
            val method = methodName?.let { runCatching { HttpMethod.valueOf(it) }.getOrNull() }
            if (method == null) reporter.error(endpointAnnotation, NomiErrors.endpointUnknownMethodName(name))
            return method
        }
        val method = METHOD_NAMES[name]
        if (method == null) reporter.error(function, NomiErrors.endpointUnknownMethodName(name))
        return method
    }

    private fun checkReceiver(function: KSFunctionDeclaration, name: String, reporter: Reporter): KSClassDeclaration? {
        val receiverRef = function.extensionReceiver
        if (receiverRef == null) {
            reporter.error(function, NomiErrors.endpointMissingReceiver(name)); return null
        }
        val receiverType = receiverRef.resolve()
        if (receiverType.isError) return null // deferred to the next round, not a validation error

        if (receiverType.nullability != Nullability.NOT_NULL) {
            reporter.error(receiverRef, NomiErrors.endpointReceiverNullable(name)); return null
        }
        if (receiverType.arguments.isNotEmpty()) {
            reporter.error(receiverRef, NomiErrors.endpointReceiverHasTypeArguments(name)); return null
        }
        val receiverDecl = receiverType.declaration as? KSClassDeclaration
        if (receiverDecl == null || !receiverDecl.isNomiResource()) {
            reporter.error(receiverRef, NomiErrors.endpointReceiverNotResource(name, receiverType.declaration.fq()))
            return null
        }
        return receiverDecl
    }

    // SPECS.adoc: "either the resource is declared in the module under compilation, or its Nomi…
    // implementation resolves from a dependency whose shared processor already ran."
    private fun checkReceiverImplAvailable(
        receiver: KSClassDeclaration,
        analyzed: AnalyzedResource,
        resolver: Resolver,
        name: String,
        reporter: Reporter,
    ): Boolean {
        if (receiver.containingFile != null) return true
        val fqImplName = "${analyzed.packageName}.${analyzed.implSimpleName}"
        if (resolver.getClassDeclarationByName(fqImplName) != null) return true
        reporter.error(receiver, NomiErrors.endpointReceiverImplMissing(name, fqImplName))
        return false
    }
}
