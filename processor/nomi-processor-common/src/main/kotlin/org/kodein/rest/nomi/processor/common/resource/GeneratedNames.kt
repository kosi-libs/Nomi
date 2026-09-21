package org.kodein.rest.nomi.processor.common.resource

// SPECS.adoc § Generated-name collisions: "Nomi" + the simple names of the resource interface and of
// every interface or plain declaration it is nested in, outermost first.
public fun nomiImplName(enclosingSimpleNames: List<String>): String =
    enclosingSimpleNames.joinToString("", prefix = "Nomi")

/**
 * "endpoints.kt" -> "NomiEndpointsResources": drop the extension, keep only identifier characters,
 * uppercase the first letter, and wrap with the fixed `Nomi`/`Resources` affixes.
 */
public fun generatedResourcesFileName(sourceFileName: String): String {
    val withoutExtension = sourceFileName.substringBeforeLast(".")
    val sanitized = withoutExtension.filter { it.isLetterOrDigit() || it == '_' }
    val capitalized = sanitized.replaceFirstChar { it.uppercaseChar() }
    return "Nomi${capitalized}Resources"
}

// SPECS.adoc § Generated-name collisions: "multipart" + the simple names of the part type and of
// every declaration it is nested in, outermost first.
public fun multipartBuilderName(enclosingSimpleNames: List<String>): String =
    enclosingSimpleNames.joinToString("", prefix = "multipart")

/** "APIServices" -> "NomiAPIServicesMultipart": the one file holding every multipart builder
 * generated on a `@ClientEndpointServiceFactory` object. */
public fun generatedMultipartFileName(objectSimpleName: String): String = "Nomi${objectSimpleName}Multipart"

/** A generated factory's identity for collision purposes: its receiver (null for a root resource's
 * top-level function) and its name. */
public data class FactoryKey(val receiverFqName: String?, val name: String)

public fun factoryKeyOf(resource: AnalyzedResource): FactoryKey =
    FactoryKey(resource.parent?.declaration?.qualifiedName?.asString(), resource.factoryName)
