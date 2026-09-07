package org.kodein.rest.nomi.processor.common.resource

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Variance
import org.kodein.rest.nomi.processor.common.NomiErrors
import org.kodein.rest.nomi.processor.common.NomiFq
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.ksp.ARRAY_FQ_NAMES
import org.kodein.rest.nomi.processor.common.ksp.annotationNamed
import org.kodein.rest.nomi.processor.common.ksp.expandTypealias
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.stringer.Registration
import org.kodein.rest.nomi.processor.common.stringer.StringerResolver

/** SPECS.md § Path parameter types. */
public object PathParamChecks {

    public fun check(
        property: KSPropertyDeclaration,
        scopes: List<List<Registration<String, KSClassDeclaration>>>,
        stringerResolver: StringerResolver,
        user: KSDeclaration,
        reporter: Reporter,
    ): PathParam? {
        val name = property.simpleName.asString()
        val rawType = property.type.resolve()
        if (rawType.isError) return null // deferred to the next round, not a validation error

        if (rawType.nullability != Nullability.NOT_NULL) {
            reporter.error(property, NomiErrors.pathParamNullable(name))
            return null
        }

        val type = rawType.expandTypealias()

        if (type.declaration is KSTypeParameter) {
            reporter.error(property, NomiErrors.pathParamTypeParameter(name)); return null
        }
        if (type.arguments.any { it.variance == Variance.STAR || it.type == null }) {
            reporter.error(property, NomiErrors.pathParamStarProjection(name)); return null
        }
        if (type.isFunctionType) {
            reporter.error(property, NomiErrors.pathParamFunctionType(name)); return null
        }

        val fqName = type.declaration.qualifiedName?.asString()
        if (fqName != null && fqName in ARRAY_FQ_NAMES) {
            reporter.error(property, NomiErrors.pathParamArray(name)); return null
        }
        if (fqName == "kotlin.Unit" || fqName == "kotlin.Nothing" || fqName == "kotlin.Any") {
            reporter.error(property, NomiErrors.pathParamForbiddenType(name, fqName)); return null
        }

        val stringed = property.annotationNamed(NomiFq.STRINGED)

        if (type.arguments.isNotEmpty() && stringed == null) {
            // A parameterized type can only be served a Stringer via @Endpoint.Stringed on this
            // specific property: @Endpoint.UseStringer(type = ...) can only name a raw class.
            reporter.error(property, NomiErrors.pathParamParameterizedNeedsStringed(name))
            return null
        }

        val stringer = stringerResolver.resolveFor(property, property.fq(), type, stringed, scopes, user) ?: return null
        return PathParam(property, name, type, stringer)
    }
}
