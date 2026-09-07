package org.kodein.rest.nomi.processor.common.resource

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import org.kodein.rest.nomi.processor.common.NomiErrors
import org.kodein.rest.nomi.processor.common.NomiFq
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.general.GeneralChecks
import org.kodein.rest.nomi.processor.common.ksp.annotationNamed
import org.kodein.rest.nomi.processor.common.ksp.declaredSupertypes
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.isNomiResource
import org.kodein.rest.nomi.processor.common.path.IDENTIFIER_REGEX

private val JVM_ANNOTATIONS_ON_PROPERTIES = listOf(
    "kotlin.jvm.JvmStatic" to "JvmStatic",
    "kotlin.jvm.JvmField" to "JvmField",
    "kotlin.jvm.JvmName" to "JvmName",
)

/** SPECS.md § Resource declarations. */
public object ResourceChecks {

    /** [parent] is the already-analyzed enclosing resource, or null for a root. Reports every
     * violation found (never stops at the first) and returns whether the declaration was clean. */
    public fun check(declaration: KSClassDeclaration, parent: AnalyzedResource?, reporter: Reporter): Boolean {
        var ok = GeneralChecks.checkReadableDeclaration(declaration, reporter)
        val name = declaration.fq()

        if (declaration.classKind != ClassKind.INTERFACE) {
            reporter.error(declaration, NomiErrors.resourceNotAnInterface(name, declaration.classKind.name.lowercase()))
            ok = false
        } else if (Modifier.FUN in declaration.modifiers) {
            reporter.error(declaration, NomiErrors.resourceIsFunInterface(name))
            ok = false
        }

        ok = checkSupertypes(declaration, parent, name, reporter) && ok

        if (declaration.annotationNamed(NomiFq.SERVICE) != null) {
            reporter.error(declaration, NomiErrors.resourceAlsoAnnotatedService(name))
            ok = false
        }

        for (nested in declaration.declarations.filterIsInstance<KSClassDeclaration>()) {
            if (nested.classKind == ClassKind.INTERFACE && nested.isNomiResource()) continue
            if (nested.classKind == ClassKind.INTERFACE) {
                reporter.error(nested, NomiErrors.nestedDeclarationNotResource(name, nested.fq()))
            } else {
                reporter.error(nested, NomiErrors.nestedDeclarationForbidden(name, nested.fq(), nested.classKind.name.lowercase()))
            }
            ok = false
        }
        for (typeAlias in declaration.declarations.filterIsInstance<KSTypeAlias>()) {
            reporter.error(typeAlias, NomiErrors.nestedDeclarationForbidden(name, typeAlias.fq(), "typealias"))
            ok = false
        }
        for (function in declaration.getDeclaredFunctions()) {
            reporter.error(function, NomiErrors.resourceHasFunction(name, function.simpleName.asString()))
            ok = false
        }

        val ancestorParamNames = parent?.allParams.orEmpty().map { it.name }.toSet()
        for (property in declaration.getDeclaredProperties()) {
            if (!checkProperty(property, ancestorParamNames, parent, reporter)) ok = false
        }

        return ok
    }

    private fun checkSupertypes(declaration: KSClassDeclaration, parent: AnalyzedResource?, name: String, reporter: Reporter): Boolean {
        var ok = true
        val supertypes = declaration.declaredSupertypes()

        if (parent == null) {
            supertypes.firstOrNull()?.let {
                reporter.error(declaration, NomiErrors.resourceRootHasSupertype(name, it.declaration.qualifiedName?.asString() ?: "?"))
                ok = false
            }
        } else {
            val parentFq = parent.declaration.qualifiedName?.asString()
            if (supertypes.none { it.declaration.qualifiedName?.asString() == parentFq }) {
                reporter.error(declaration, NomiErrors.resourceNestedWrongSupertype(name, parent.declaration.fq()))
                ok = false
            }
            for (s in supertypes) {
                if (s.declaration.qualifiedName?.asString() != parentFq) {
                    reporter.error(declaration, NomiErrors.resourceExtraSupertype(name, s.declaration.qualifiedName?.asString() ?: "?"))
                    ok = false
                }
            }
        }

        if (supertypes.any { it.declaration.qualifiedName?.asString() == NomiFq.ENDPOINT_SERVICE }) {
            reporter.error(declaration, NomiErrors.resourceExtendsEndpointService(name))
            ok = false
        }
        return ok
    }

    private fun checkProperty(
        property: KSPropertyDeclaration,
        ancestorParamNames: Set<String>,
        parent: AnalyzedResource?,
        reporter: Reporter,
    ): Boolean {
        var ok = true
        val name = property.simpleName.asString()

        if (property.isMutable) {
            reporter.error(property, NomiErrors.propertyIsVar(name)); ok = false
        }
        if (!property.isAbstract() || property.isDelegated()) {
            reporter.error(property, NomiErrors.propertyNotAbstract(name)); ok = false
        }
        if (property.extensionReceiver != null) {
            reporter.error(property, NomiErrors.propertyIsExtension(name)); ok = false
        }
        if (property.typeParameters.isNotEmpty()) {
            reporter.error(property, NomiErrors.propertyHasTypeParameters(name)); ok = false
        }
        if (property.findOverridee() != null || Modifier.OVERRIDE in property.modifiers) {
            reporter.error(property, NomiErrors.propertyOverrides(name)); ok = false
        }
        if (!IDENTIFIER_REGEX.matches(name)) {
            reporter.error(property, NomiErrors.propertyNameInvalid(name)); ok = false
        }
        if (name in ancestorParamNames) {
            reporter.error(property, NomiErrors.propertyShadowsAncestor(name, parent?.declaration?.fq() ?: "?")); ok = false
        }
        if (Modifier.CONST in property.modifiers || Modifier.LATEINIT in property.modifiers) {
            reporter.error(property, NomiErrors.propertyConstOrLateinit(name)); ok = false
        }
        for ((fqName, simpleName) in JVM_ANNOTATIONS_ON_PROPERTIES) {
            if (property.annotationNamed(fqName) != null) {
                reporter.error(property, NomiErrors.propertyHasJvmAnnotation(name, simpleName)); ok = false
            }
        }
        property.annotationNamed(NomiFq.STRINGED)?.let { ann ->
            if (!GeneralChecks.checkUseSiteTarget(ann, property, "Endpoint.Stringed", reporter)) ok = false
        }

        return ok
    }
}
