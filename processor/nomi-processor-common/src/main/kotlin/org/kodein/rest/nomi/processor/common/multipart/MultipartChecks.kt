package org.kodein.rest.nomi.processor.common.multipart

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import org.kodein.rest.nomi.processor.common.NomiErrors
import org.kodein.rest.nomi.processor.common.NomiFq
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.general.GeneralChecks
import org.kodein.rest.nomi.processor.common.ksp.ARRAY_FQ_NAMES
import org.kodein.rest.nomi.processor.common.ksp.annotationNamed
import org.kodein.rest.nomi.processor.common.ksp.declaredSupertypes
import org.kodein.rest.nomi.processor.common.ksp.enumArgName
import org.kodein.rest.nomi.processor.common.ksp.expandTypealias
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.stringArg
import org.kodein.rest.nomi.processor.common.stringer.Registration
import org.kodein.rest.nomi.processor.common.stringer.StringerResolver

// The effective part name allows '.' and '-' just as a query parameter name does -
// SPECS.adoc § Multipart part types.
private val PART_NAME_REGEX = Regex("[A-Za-z_][A-Za-z0-9_.-]*")

/** Every rule of SPECS.adoc § Multipart part types. */
public object MultipartChecks {

    /** Validates the part type's own shape, returning its parts, or `null` having reported every
     * violation. */
    public fun check(
        declaration: KSClassDeclaration,
        scopes: List<List<Registration<String, KSClassDeclaration>>>,
        stringerResolver: StringerResolver,
        user: KSDeclaration,
        reporter: Reporter,
    ): List<MultipartPart>? {
        var ok = GeneralChecks.checkReadableDeclaration(declaration, reporter)
        val partTypeName = declaration.fq()

        // SPECS.adoc § Multipart part types: "Must be a `sealed interface`".
        if (declaration.classKind != ClassKind.INTERFACE || Modifier.SEALED !in declaration.modifiers) {
            val kind = if (declaration.classKind == ClassKind.INTERFACE) "a non-sealed interface"
            else "a ${declaration.classKind.name.lowercase()}"
            reporter.error(declaration, NomiErrors.partTypeNotSealedInterface(partTypeName, kind))
            ok = false
        }
        if (!ok) return null

        // SPECS.adoc § Multipart part types: "Every part must be a direct subtype nested inside the
        // part type itself" - so the parts are exactly its nested classes, never a global subtype scan.
        val nested = declaration.declarations.filterIsInstance<KSClassDeclaration>()
            .filterNot { it.isCompanionObject }
            .toList()

        if (nested.isEmpty()) {
            reporter.error(declaration, NomiErrors.partTypeNoParts(partTypeName))
            return null
        }

        val parts = mutableListOf<MultipartPart>()
        for (subtype in nested) {
            val part = checkPart(declaration, partTypeName, subtype, scopes, stringerResolver, user, reporter)
            if (part == null) ok = false else parts += part
        }
        if (!ok) return null

        if (!checkNoNameCollisions(partTypeName, parts, reporter)) return null

        return parts
    }

    private fun checkPart(
        partType: KSClassDeclaration,
        partTypeName: String,
        subtype: KSClassDeclaration,
        scopes: List<List<Registration<String, KSClassDeclaration>>>,
        stringerResolver: StringerResolver,
        user: KSDeclaration,
        reporter: Reporter,
    ): MultipartPart? {
        var ok = GeneralChecks.checkReadableDeclaration(subtype, reporter)
        val partName = subtype.fq()

        // SPECS.adoc § Multipart part types: "Every part must be a `data class`".
        if (subtype.classKind != ClassKind.CLASS || Modifier.DATA !in subtype.modifiers) {
            val kind = if (subtype.classKind == ClassKind.CLASS) "a non-data class"
            else "a ${subtype.classKind.name.lowercase()}"
            reporter.error(subtype, NomiErrors.partNotDataClass(partTypeName, partName, kind))
            ok = false
        }
        if (subtype.typeParameters.isNotEmpty()) {
            reporter.error(subtype, NomiErrors.partHasTypeParameters(partName))
            ok = false
        }
        // A part must be a subtype of the part type it is nested in, and of nothing else.
        val partTypeFq = partType.qualifiedName?.asString()
        if (subtype.declaredSupertypes().none { it.declaration.qualifiedName?.asString() == partTypeFq }) {
            reporter.error(subtype, NomiErrors.partNotNestedInPartType(partTypeName, partName))
            ok = false
        }
        if (!ok) return null

        val properties = subtype.getDeclaredProperties().toList()
        if (properties.size != 1) {
            reporter.error(subtype, NomiErrors.partPropertyCount(partName, properties.size))
            return null
        }
        val property = properties.single()
        if (property.isMutable) {
            reporter.error(property, NomiErrors.partPropertyIsVar(partName, property.simpleName.asString()))
            ok = false
        }

        val annotation = subtype.annotationNamed(NomiFq.PART)
        val wireName = annotation?.stringArg("name")?.takeIf { it.isNotEmpty() }
            ?: subtype.simpleName.asString().replaceFirstChar { it.lowercaseChar() }
        if (wireName.isBlank() || !PART_NAME_REGEX.matches(wireName)) {
            reporter.error(annotation ?: subtype, NomiErrors.partNameInvalid(partName, wireName))
            ok = false
        }

        // An absent `presence` argument means the annotation's own default - and a defaulted
        // argument may simply not be present at all when the part type is read from a compiled
        // dependency, so the default is restated here rather than relied upon.
        val presenceName = annotation?.enumArgName("presence") ?: PartPresence.Required.name
        val presence = runCatching { PartPresence.valueOf(presenceName) }.getOrNull()
        if (presence == null) {
            reporter.error(annotation ?: subtype, NomiErrors.partPresenceUnknown(partName, presenceName))
            ok = false
        }

        val rawType = property.type.resolve()
        if (rawType.isError) return null // deferred to the next round, not a validation error
        val payloadType = rawType.expandTypealias().makeNotNullable()

        val payloadFq = payloadType.declaration.qualifiedName?.asString()
        val forbidden = when {
            payloadType.declaration is KSTypeParameter -> "a type parameter"
            payloadType.isFunctionType -> "a functional type"
            payloadFq in ARRAY_FQ_NAMES -> "an array"
            payloadFq == "kotlin.Unit" || payloadFq == "kotlin.Nothing" || payloadFq == "kotlin.Any" -> payloadFq
            else -> null
        }
        if (forbidden != null) {
            reporter.error(property, NomiErrors.partPayloadForbiddenType(partName, forbidden))
            return null
        }
        if (payloadFq == NomiFq.ENDPOINT_BINARY) {
            reporter.error(property, NomiErrors.partPayloadIsEndpointBinary(partName))
            return null
        }
        if (!GeneralChecks.checkAtLeastAsVisible(payloadType.declaration, user, reporter)) ok = false

        val payload = if (payloadFq == NomiFq.ENDPOINT_FILE) {
            PartPayload.File
        } else {
            // @Endpoint.Stringed lands on the primary-constructor VALUE PARAMETER of a data class,
            // not on the property - SPECS.adoc § Gotchas. Look in both places.
            val stringed = property.annotationNamed(NomiFq.STRINGED)
                ?: subtype.primaryConstructor?.parameters
                    ?.firstOrNull { it.name?.asString() == property.simpleName.asString() }
                    ?.annotationNamed(NomiFq.STRINGED)
            val stringer = stringerResolver.resolveFor(property, partName, payloadType, stringed, scopes, user)
                ?: return null
            PartPayload.Value(stringer)
        }

        if (!ok || presence == null) return null

        return MultipartPart(
            declaration = subtype,
            property = property,
            parameterName = subtype.simpleName.asString().replaceFirstChar { it.lowercaseChar() },
            wireName = wireName,
            presence = presence,
            payloadType = payloadType,
            payload = payload,
        )
    }

    private fun checkNoNameCollisions(partTypeName: String, parts: List<MultipartPart>, reporter: Reporter): Boolean {
        var ok = true
        val seen = mutableSetOf<String>()
        for (part in parts) {
            if (!seen.add(part.wireName)) {
                reporter.error(part.declaration, NomiErrors.partNameCollision(partTypeName, part.wireName))
                ok = false
            }
        }
        return ok
    }
}
