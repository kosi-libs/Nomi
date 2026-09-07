package org.kodein.rest.nomi.processor.common.resource

// SPECS.md § Generated-name collisions: "Nomi" + the simple names of the resource interface and of
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

/** A generated factory's identity for collision purposes: its receiver (null for a root resource's
 * top-level function) and its name. */
public data class FactoryKey(val receiverFqName: String?, val name: String)

public fun factoryKeyOf(resource: AnalyzedResource): FactoryKey =
    FactoryKey(resource.parent?.declaration?.qualifiedName?.asString(), resource.factoryName)
