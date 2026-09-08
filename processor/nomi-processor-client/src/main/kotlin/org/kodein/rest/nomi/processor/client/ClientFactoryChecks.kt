package org.kodein.rest.nomi.processor.client

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.general.GeneralChecks
import org.kodein.rest.nomi.processor.common.ksp.annotationsNamed
import org.kodein.rest.nomi.processor.common.ksp.classArg
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.isNomiResource
import org.kodein.rest.nomi.processor.common.ksp.isNomiService
import org.kodein.rest.nomi.processor.common.ksp.stringArg
import org.kodein.rest.nomi.processor.common.path.IDENTIFIER_REGEX

// SPECS.md: "name must be non-blank, match [A-Za-z_][A-Za-z0-9_]*, and must not be a Kotlin hard
// keyword." Soft keywords (`by`, `get`, `set`, ...) are fine as identifiers, so only the hard set.
private val HARD_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "val", "var", "when", "while",
)

/** SPECS.md § Client factories. */
public object ClientFactoryChecks {

    /**
     * Reports every violation found and returns the `@ClientEndpointServiceFactory` registrations
     * [declaration] carries that individually passed - possibly empty, possibly partial, since a bad
     * registration doesn't poison a sibling one on the same object. Returns `null` only when
     * [declaration] itself (the annotated object's own shape) is invalid.
     */
    public fun check(declaration: KSClassDeclaration, reporter: Reporter): List<ClientFactoryRegistration>? {
        var ok = GeneralChecks.checkReadableDeclaration(declaration, reporter)
        val name = declaration.fq()

        if (declaration.classKind != ClassKind.OBJECT) {
            reporter.error(declaration, NomiClientErrors.notAnObject(name, declaration.classKind.name.lowercase()))
            ok = false
        } else if (declaration.isCompanionObject) {
            reporter.error(declaration, NomiClientErrors.isCompanionObject(name))
            ok = false
        }

        ok = checkPlacement(declaration, name, reporter) && ok
        if (!ok) return null

        val seenServiceClasses = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        val declaredMemberNames = declaration.getDeclaredFunctions().map { it.simpleName.asString() }.toSet()

        val registrations = mutableListOf<ClientFactoryRegistration>()
        for (annotation in declaration.annotationsNamed(CLIENT_ENDPOINT_SERVICE_FACTORY_FQ)) {
            val registration = checkRegistration(declaration, name, annotation, seenServiceClasses, seenNames, declaredMemberNames, reporter)
            if (registration != null) registrations += registration
        }
        return registrations
    }

    private fun checkPlacement(declaration: KSClassDeclaration, name: String, reporter: Reporter): Boolean {
        var ok = true
        var current = declaration.parentDeclaration
        while (current is KSClassDeclaration) {
            if (current.isNomiResource()) {
                reporter.error(declaration, NomiClientErrors.nestedInResource(name, current.fq()))
                ok = false
            }
            if (!GeneralChecks.checkReadableDeclaration(current, reporter)) ok = false
            current = current.parentDeclaration
        }
        return ok
    }

    private fun checkRegistration(
        declaration: KSClassDeclaration,
        objectName: String,
        annotation: KSAnnotation,
        seenServiceClasses: MutableSet<String>,
        seenNames: MutableSet<String>,
        declaredMemberNames: Set<String>,
        reporter: Reporter,
    ): ClientFactoryRegistration? {
        var ok = true

        val serviceClass = annotation.classArg("serviceClass")
        if (serviceClass == null || !serviceClass.isNomiService()) {
            reporter.error(annotation, NomiClientErrors.serviceClassNotAService(objectName, serviceClass?.fq() ?: "?"))
            ok = false
        } else if (!seenServiceClasses.add(serviceClass.fq())) {
            reporter.error(annotation, NomiClientErrors.serviceClassRepeated(objectName, serviceClass.fq()))
            ok = false
        }

        val factoryName = annotation.stringArg("name")
        if (factoryName == null || factoryName.isBlank() || !IDENTIFIER_REGEX.matches(factoryName)) {
            reporter.error(annotation, NomiClientErrors.nameBlankOrInvalid(objectName, factoryName ?: "?"))
            ok = false
        } else {
            if (factoryName in HARD_KEYWORDS) {
                reporter.error(annotation, NomiClientErrors.nameIsHardKeyword(objectName, factoryName)); ok = false
            }
            if (!seenNames.add(factoryName)) {
                reporter.error(annotation, NomiClientErrors.nameRepeated(objectName, factoryName)); ok = false
            }
            if (factoryName in declaredMemberNames) {
                reporter.error(annotation, NomiClientErrors.nameCollidesWithExistingMember(objectName, factoryName)); ok = false
            }
        }

        if (!ok || serviceClass == null || factoryName == null) return null
        return ClientFactoryRegistration(annotation, serviceClass, factoryName)
    }
}
