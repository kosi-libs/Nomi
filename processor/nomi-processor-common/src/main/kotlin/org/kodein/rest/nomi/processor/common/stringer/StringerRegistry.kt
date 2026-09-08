package org.kodein.rest.nomi.processor.common.stringer

/** One `@Endpoint.UseStringer`-shaped registration, found at annotation-tree [depth]. */
public data class Registration<T, S>(val type: T, val stringer: S, val depth: Int)

public sealed interface StringerResolution<S> {
    public data class Resolved<S>(val stringer: S, val distance: Int, val depth: Int) : StringerResolution<S>
    public data class Ambiguous<S>(val candidates: List<S>, val distance: Int, val depth: Int) : StringerResolution<S>
    public class Unresolved<S> : StringerResolution<S>
}

/**
 * Resolves a `Stringer` for [type] per SPECS.adoc § Stringers' nearest-wins algorithm: [scopes] are
 * ordered nearest-interface-first (index 0 == the declaring interface itself); within a scope,
 * only its shallowest-depth registrations for [type] are considered. Interface distance always
 * dominates annotation depth - a scope is tried only after every registration in every nearer scope
 * has been checked and none of them named [type].
 */
public fun <T, S> resolveStringer(
    scopes: List<List<Registration<T, S>>>,
    type: T,
    sameStringer: (S, S) -> Boolean,
): StringerResolution<S> {
    for ((distance, scope) in scopes.withIndex()) {
        val matches = scope.filter { it.type == type }
        if (matches.isEmpty()) continue

        val shallowestDepth = matches.minOf { it.depth }
        val shallowest = matches.filter { it.depth == shallowestDepth }

        val distinct = mutableListOf<S>()
        for (m in shallowest) {
            if (distinct.none { sameStringer(it, m.stringer) }) distinct += m.stringer
        }

        return if (distinct.size == 1) {
            StringerResolution.Resolved(distinct[0], distance, shallowestDepth)
        } else {
            StringerResolution.Ambiguous(distinct, distance, shallowestDepth)
        }
    }
    return StringerResolution.Unresolved()
}
