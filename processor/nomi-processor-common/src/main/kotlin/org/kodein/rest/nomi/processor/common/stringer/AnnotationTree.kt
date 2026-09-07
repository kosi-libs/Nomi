package org.kodein.rest.nomi.processor.common.stringer

/** A value found while walking an annotation tree, tagged with the depth it was found at. */
public data class Found<R>(val value: R, val depth: Int)

/**
 * Walks the annotation tree reachable from [roots] breadth-first, so each node is visited at its
 * minimum depth and a registration is naturally recorded at the shallowest depth it appears at
 * (SPECS.md § Stringers: "the whole annotation tree reachable from a declaration is walked ...
 * each registration is recorded at the shallowest depth it is found at"). Guards against a cycle
 * and collapses a diamond into a single visit - but the [roots] themselves are never deduped
 * against each other, since a `@Repeatable` annotation applied more than once produces several
 * roots that share a class identity yet carry independent values, and all of them must count
 * (SPECS.md: "@Endpoint.UseStringer ... is repeatable, so multiple Stringers may be registered to
 * the same interface"). Dedup only ever prevents *re-discovering* an identity through [childrenOf].
 *
 * [identityOf] returns `null` for a node that cannot be identified (e.g. an unresolvable type),
 * in which case it is never deduped against anything.
 */
public fun <A, R> collectFromAnnotationTree(
    roots: List<A>,
    identityOf: (A) -> String?,
    childrenOf: (A) -> List<A>,
    valuesOf: (A) -> List<R>,
    onVisit: (node: A, depth: Int) -> Unit = { _, _ -> },
): List<Found<R>> {
    val found = mutableListOf<Found<R>>()
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<Pair<A, Int>>()

    for (root in roots) {
        queue.addLast(root to 0)
        identityOf(root)?.let { visited.add(it) }
    }

    while (queue.isNotEmpty()) {
        val (node, depth) = queue.removeFirst()
        onVisit(node, depth)
        for (value in valuesOf(node)) found += Found(value, depth)
        for (child in childrenOf(node)) {
            val identity = identityOf(child)
            if (identity == null || visited.add(identity)) queue.addLast(child to depth + 1)
        }
    }

    return found
}
