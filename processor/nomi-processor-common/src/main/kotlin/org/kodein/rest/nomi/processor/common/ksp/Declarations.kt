package org.kodein.rest.nomi.processor.common.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Visibility
import org.kodein.rest.nomi.processor.common.NomiFq

public fun KSDeclaration.fq(): String = qualifiedName?.asString() ?: simpleName.asString()

/** Resolves a type through any chain of `typealias` expansions (SPECS.md: "resolved to its expansion"). */
public fun KSType.expandTypealias(): KSType {
    var current = this
    while (current.declaration is KSTypeAlias) {
        current = (current.declaration as KSTypeAlias).type.resolve()
    }
    return current
}

/**
 * Direct supertypes, excluding `kotlin.Any` - which [KSClassDeclaration.superTypes] includes even
 * for an interface with no declared supertype at all.
 */
public fun KSClassDeclaration.declaredSupertypes(): List<KSType> =
    superTypes.map { it.resolve() }.filter { it.declaration.qualifiedName?.asString() != "kotlin.Any" }.toList()

public fun KSClassDeclaration.isNomiResource(): Boolean = annotationsNamed(NomiFq.RESOURCE).any()

/** Every enclosing class-like declaration, outermost first. Empty for a top-level declaration. */
public fun KSClassDeclaration.enclosingChain(): List<KSClassDeclaration> {
    val chain = mutableListOf<KSClassDeclaration>()
    var current = parentDeclaration
    while (current is KSClassDeclaration) {
        chain += current
        current = current.parentDeclaration
    }
    return chain.asReversed()
}

public fun KSDeclaration.visibilityOrNull(): Visibility = getVisibility()
