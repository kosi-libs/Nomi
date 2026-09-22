package org.kodein.rest.nomi.processor.common.service

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Variance
import org.kodein.rest.nomi.processor.common.NomiErrors
import org.kodein.rest.nomi.processor.common.NomiFq
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.general.GeneralChecks
import org.kodein.rest.nomi.processor.common.ksp.expandTypealias
import org.kodein.rest.nomi.processor.common.ksp.fq

/** SPECS.adoc § Endpoint return types. */
public object ReturnTypeChecks {

    /** Returns the endpoint's `(V, E)` type-argument pair, having reported every violation, or
     * `null` for an invalid return type. */
    public fun check(function: KSFunctionDeclaration, user: KSDeclaration, reporter: Reporter): Pair<KSType, KSType>? {
        val name = function.simpleName.asString()
        val returnRef = function.returnType
        if (returnRef == null) {
            reporter.error(function, NomiErrors.endpointReturnNotEither(name)); return null
        }

        val rawReturn = returnRef.resolve()
        if (rawReturn.isError) return null // deferred to the next round, not a validation error

        val expanded = rawReturn.expandTypealias()

        if (expanded.declaration.qualifiedName?.asString() != NomiFq.EITHER) {
            reporter.error(function, NomiErrors.endpointReturnNotEither(name)); return null
        }
        if (expanded.isMarkedNullable) {
            reporter.error(function, NomiErrors.endpointReturnNullable(name)); return null
        }
        if (expanded.arguments.any { it.variance == Variance.STAR || it.type == null }) {
            reporter.error(function, NomiErrors.endpointReturnStarProjection(name)); return null
        }

        val valueArg = expanded.arguments.getOrNull(0)?.type?.resolve()
        val errorArg = expanded.arguments.getOrNull(1)?.type?.resolve()
        if (valueArg == null || errorArg == null) {
            reporter.error(function, NomiErrors.endpointReturnNotEither(name)); return null
        }
        if (valueArg.declaration is KSTypeParameter || errorArg.declaration is KSTypeParameter) {
            reporter.error(function, NomiErrors.endpointReturnTypeArgNotConcrete(name)); return null
        }

        var ok = checkError(function, name, errorArg, reporter)
        ok = checkValue(function, name, valueArg, reporter) && ok
        ok = checkDisjoint(function, name, valueArg, errorArg, reporter) && ok
        ok = GeneralChecks.checkAtLeastAsVisible(valueArg.declaration, user, reporter) && ok
        ok = GeneralChecks.checkAtLeastAsVisible(errorArg.declaration, user, reporter) && ok

        return if (ok) valueArg to errorArg else null
    }

    private fun checkError(function: KSFunctionDeclaration, name: String, errorArg: KSType, reporter: Reporter): Boolean {
        val fqName = errorArg.declaration.qualifiedName?.asString()
        if (fqName == NomiFq.ERROR) {
            reporter.error(function, NomiErrors.endpointReturnErrorIsErrorItself(name)); return false
        }
        if (fqName == "kotlin.Nothing") {
            reporter.error(function, NomiErrors.endpointReturnErrorIsNothing(name)); return false
        }
        if (errorArg.isMarkedNullable) {
            reporter.error(function, NomiErrors.endpointReturnErrorNullable(name)); return false
        }
        val decl = errorArg.declaration as? KSClassDeclaration
        val implementsError = decl != null && decl.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == NomiFq.ERROR }
        if (!implementsError) {
            reporter.error(function, NomiErrors.endpointReturnErrorNotImplementing(name, errorArg.declaration.fq()))
            return false
        }
        return true
    }

    private fun checkValue(function: KSFunctionDeclaration, name: String, valueArg: KSType, reporter: Reporter): Boolean {
        val fqName = valueArg.declaration.qualifiedName?.asString()
        if (fqName == "kotlin.Nothing") {
            reporter.error(function, NomiErrors.endpointReturnValueForbidden(name, "Nothing")); return false
        }
        if (fqName == "kotlin.Any") {
            reporter.error(function, NomiErrors.endpointReturnValueForbidden(name, if (valueArg.isMarkedNullable) "Any?" else "Any")); return false
        }
        if (fqName == "kotlin.Unit" && valueArg.isMarkedNullable) {
            reporter.error(function, NomiErrors.endpointReturnValueForbidden(name, "Unit?")); return false
        }
        if (fqName == NomiFq.NO_FAIL) {
            reporter.error(function, NomiErrors.endpointReturnValueIsNoFail(name)); return false
        }
        // An EndpointFile is one part of a multipart body; a whole streamed response is an
        // EndpointBinary, exactly as it is for a request body.
        if (fqName == NomiFq.ENDPOINT_FILE) {
            reporter.error(function, NomiErrors.returnValueIsEndpointFile(name)); return false
        }
        // A streamed response is bytes or it is nothing: null would have to travel as 204, which is
        // what Either<Unit, E> already means.
        if (fqName == NomiFq.ENDPOINT_BINARY && valueArg.isMarkedNullable) {
            reporter.error(function, NomiErrors.returnValueIsNullableBinary(name)); return false
        }
        return true
    }

    // SPECS.adoc: V and E must be disjoint - neither may be a subtype of the other, and they must not
    // be the same type; Either discriminates value from failure with isInstance at runtime.
    private fun checkDisjoint(function: KSFunctionDeclaration, name: String, valueArg: KSType, errorArg: KSType, reporter: Reporter): Boolean {
        val v = valueArg.makeNotNullable()
        val e = errorArg.makeNotNullable()
        if (v.isAssignableFrom(e) || e.isAssignableFrom(v)) {
            reporter.error(function, NomiErrors.endpointReturnValueErrorOverlap(name, valueArg.declaration.fq(), errorArg.declaration.fq()))
            return false
        }
        return true
    }
}
