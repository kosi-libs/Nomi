package org.kodein.rest.nomi.processor.common.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Visibility
import org.kodein.rest.nomi.processor.common.NomiFq

public fun KSDeclaration.fq(): String = qualifiedName?.asString() ?: simpleName.asString()

/**
 * Resolves a type through any chain of `typealias` expansions (SPECS.adoc: "resolved to its
 * expansion"), substituting the alias's own type parameters with its use-site arguments along the
 * way - `typealias Ok<V> = Either<V, NoFail>` used as `Ok<UserDetails>` expands to
 * `Either<UserDetails, NoFail>`, not `Either<V, NoFail>` with `V` left dangling. A use-site or
 * alias-body nullability (`Ok<UserDetails>?`, or a typealias body declared nullable) is preserved.
 */
public fun KSType.expandTypealias(): KSType {
    var current = this
    while (true) {
        val alias = current.declaration as? KSTypeAlias ?: return current
        val substitution = alias.typeParameters.zip(current.arguments).toMap()
        val expanded = substituteTypeParameters(alias.type.resolve(), substitution)
        current = if (current.isMarkedNullable && !expanded.isMarkedNullable) expanded.makeNullable() else expanded
    }
}

/**
 * Substitutes every bare occurrence of one of [substitution]'s type parameters - as the given
 * [type] itself, or as one of its own immediate type arguments - with the corresponding argument
 * from the alias's use site. A substituted argument is always the original, already-resolved
 * [KSTypeArgument] from that use site (its [KSTypeReference][com.google.devtools.ksp.symbol.KSTypeReference]
 * is real source syntax) - never a synthesized one, since KSP offers no reflection-free way to
 * build a fresh [KSType] into a [KSTypeArgument]. A type parameter nested deeper than one type
 * argument down (`typealias W<V> = Either<List<V>, NoFail>`) is therefore left unsubstituted; no
 * declaration Nomi reads needs that shape today.
 */
private fun substituteTypeParameters(type: KSType, substitution: Map<KSTypeParameter, KSTypeArgument>): KSType {
    val decl = type.declaration
    if (decl is KSTypeParameter) {
        val replacementType = substitution[decl]?.type?.resolve() ?: return type
        return if (type.isMarkedNullable) replacementType.makeNullable() else replacementType
    }
    if (type.arguments.isEmpty()) return type
    var changed = false
    val newArgs = type.arguments.map { arg ->
        val argDecl = arg.type?.resolve()?.declaration
        val replacement = (argDecl as? KSTypeParameter)?.let { substitution[it] }
        if (replacement != null) { changed = true; replacement } else arg
    }
    return if (changed) type.replace(newArgs) else type
}

/**
 * Direct supertypes, excluding `kotlin.Any` - which [KSClassDeclaration.superTypes] includes even
 * for an interface with no declared supertype at all.
 */
public fun KSClassDeclaration.declaredSupertypes(): List<KSType> =
    superTypes.map { it.resolve() }.filter { it.declaration.qualifiedName?.asString() != "kotlin.Any" }.toList()

public fun KSClassDeclaration.isNomiResource(): Boolean = annotationsNamed(NomiFq.RESOURCE).any()

public fun KSClassDeclaration.isNomiService(): Boolean = annotationsNamed(NomiFq.SERVICE).any()

/** Every array type's FQN - forbidden wherever Nomi reads a type that must resolve a `Stringer`
 * (a resource path parameter, an endpoint query parameter). Shared so both live in exactly one place. */
public val ARRAY_FQ_NAMES: Set<String> = setOf(
    "kotlin.Array",
    "kotlin.BooleanArray", "kotlin.ByteArray", "kotlin.CharArray", "kotlin.DoubleArray",
    "kotlin.FloatArray", "kotlin.IntArray", "kotlin.LongArray", "kotlin.ShortArray",
    "kotlin.UByteArray", "kotlin.UIntArray", "kotlin.ULongArray", "kotlin.UShortArray",
)

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
