package org.kodein.rest.nomi.processor.common.stringer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A minimal annotation-class graph: each node has an identity, a list of "carrier" annotations
// applied to it (children in the walk), and the @Endpoint.UseStringer-shaped values it carries directly.
private data class Node(val id: String, val children: List<Node> = emptyList(), val values: List<String> = emptyList())

private fun walk(vararg roots: Node): List<Found<String>> =
    collectFromAnnotationTree(
        roots = roots.toList(),
        identityOf = { it.id },
        childrenOf = { it.children },
        valuesOf = { it.values },
    )

class AnnotationTreeWalkTests {

    @Test
    fun `a direct annotation is found at depth 0`() {
        val root = Node("R", values = listOf("direct"))
        val found = walk(root)
        assertEquals(listOf(Found("direct", 0)), found)
    }

    @Test
    fun `a value reached through one carrier is found at depth 1`() {
        val carrier = Node("Carrier", values = listOf("viaCarrier"))
        val root = Node("R", children = listOf(carrier))
        val found = walk(root)
        assertEquals(listOf(Found("viaCarrier", 1)), found)
    }

    @Test
    fun `a two-level carrier chain is found at depth 2`() {
        val leaf = Node("Leaf", values = listOf("deep"))
        val mid = Node("Mid", children = listOf(leaf))
        val root = Node("R", children = listOf(mid))
        val found = walk(root)
        assertEquals(listOf(Found("deep", 2)), found)
    }

    @Test
    fun `a cyclic annotation reference terminates`() {
        // A's own child is another node sharing A's identity - a direct self-cycle.
        val cyclicChild = Node("A", values = listOf("never reached again"))
        val root = Node("A", children = listOf(cyclicChild))
        val found = walk(root)
        // Only the root visit's own values are recorded; the child (same identity "A") is skipped.
        assertEquals(emptyList(), found)
    }

    @Test
    fun `a diamond is visited once and recorded at the shallow depth`() {
        val bottom = Node("Bottom", values = listOf("shared"))
        val left = Node("Left", children = listOf(bottom))
        val right = Node("Right", children = listOf(bottom))
        val root = Node("R", children = listOf(left, right))
        val found = walk(root)
        assertEquals(listOf(Found("shared", 2)), found)
    }

    @Test
    fun `an unidentifiable node is still walked but never dedups against another`() {
        val found = collectFromAnnotationTree(
            roots = listOf(Node("_unresolved_", values = listOf("x")), Node("_unresolved_", values = listOf("y"))),
            identityOf = { null },
            childrenOf = { it.children },
            valuesOf = { it.values },
        )
        assertEquals(setOf("x", "y"), found.map { it.value }.toSet())
    }

    @Test
    fun `onVisit sees every visited node exactly once with its depth`() {
        val bottom = Node("Bottom")
        val left = Node("Left", children = listOf(bottom))
        val right = Node("Right", children = listOf(bottom))
        val root = Node("R", children = listOf(left, right))
        val visits = mutableListOf<Pair<String, Int>>()
        collectFromAnnotationTree(
            roots = listOf(root),
            identityOf = { it.id },
            childrenOf = { it.children },
            valuesOf = { it.values },
            onVisit = { node, depth -> visits += node.id to depth },
        )
        assertEquals(listOf("R" to 0, "Left" to 1, "Right" to 1, "Bottom" to 2), visits)
    }

    @Test
    fun `registrations from several roots are merged`() {
        val a = Node("A", values = listOf("fromA"))
        val b = Node("B", values = listOf("fromB"))
        val found = walk(a, b)
        assertTrue(found.map { it.value }.containsAll(listOf("fromA", "fromB")))
    }

    @Test
    fun `repeated roots of the same annotation class are never deduped against each other`() {
        // @Repeatable @Endpoint.UseStringer(...) applied twice directly on one interface: same
        // class identity, different data - both must be recorded (SPECS.adoc: "it is repeatable, so
        // multiple Stringers may be registered to the same interface").
        val first = Node("UseStringer", values = listOf("Uuid -> UuidStringer"))
        val second = Node("UseStringer", values = listOf("Instant -> InstantStringer"))
        val found = walk(first, second)
        assertEquals(setOf("Uuid -> UuidStringer", "Instant -> InstantStringer"), found.map { it.value }.toSet())
    }
}
