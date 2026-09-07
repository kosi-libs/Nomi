package org.kodein.rest.nomi.processor.common.stringer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StringerResolutionTests {

    private fun scope(vararg regs: Registration<String, String>): List<Registration<String, String>> = regs.toList()
    private val same: (String, String) -> Boolean = { a, b -> a == b }

    @Test
    fun `resolves to the only registration in the nearest scope that has one`() {
        val scopes = listOf(
            scope(), // nothing at distance 0
            scope(Registration("Uuid", "UuidStringer", depth = 0)),
        )
        val r = assertIs<StringerResolution.Resolved<String>>(resolveStringer(scopes, "Uuid", same))
        assertEquals("UuidStringer", r.stringer)
        assertEquals(1, r.distance)
    }

    @Test
    fun `within one scope the shallower depth wins`() {
        val scopes = listOf(
            scope(
                Registration("Uuid", "Deep", depth = 2),
                Registration("Uuid", "Shallow", depth = 1),
            ),
        )
        val r = assertIs<StringerResolution.Resolved<String>>(resolveStringer(scopes, "Uuid", same))
        assertEquals("Shallow", r.stringer)
        assertEquals(1, r.depth)
    }

    @Test
    fun `a nearer scope wins over a farther one regardless of relative depths`() {
        val scopes = listOf(
            scope(Registration("Uuid", "Near", depth = 5)),
            scope(Registration("Uuid", "Far", depth = 0)),
        )
        val r = assertIs<StringerResolution.Resolved<String>>(resolveStringer(scopes, "Uuid", same))
        assertEquals("Near", r.stringer)
        assertEquals(0, r.distance)
    }

    @Test
    fun `different stringers tied at the same distance and depth are ambiguous`() {
        val scopes = listOf(
            scope(
                Registration("Uuid", "A", depth = 0),
                Registration("Uuid", "B", depth = 0),
            ),
        )
        val r = assertIs<StringerResolution.Ambiguous<String>>(resolveStringer(scopes, "Uuid", same))
        assertEquals(setOf("A", "B"), r.candidates.toSet())
    }

    @Test
    fun `the same stringer named twice at the same distance and depth is a harmless duplicate`() {
        val scopes = listOf(
            scope(
                Registration("Uuid", "A", depth = 0),
                Registration("Uuid", "A", depth = 0),
            ),
        )
        val r = assertIs<StringerResolution.Resolved<String>>(resolveStringer(scopes, "Uuid", same))
        assertEquals("A", r.stringer)
    }

    @Test
    fun `no registration anywhere is unresolved`() {
        assertIs<StringerResolution.Unresolved<String>>(resolveStringer(listOf(scope()), "Uuid", same))
    }

    @Test
    fun `empty scopes list is unresolved`() {
        assertIs<StringerResolution.Unresolved<String>>(resolveStringer(emptyList(), "Uuid", same))
    }

    @Test
    fun `a registration for a different type is ignored`() {
        val scopes = listOf(scope(Registration("Int", "IntStringer", depth = 0)))
        assertIs<StringerResolution.Unresolved<String>>(resolveStringer(scopes, "Uuid", same))
    }

    @Test
    fun `a scope with no match for the type is skipped in favor of the next scope`() {
        val scopes = listOf(
            scope(Registration("Int", "IntStringer", depth = 0)),
            scope(Registration("Uuid", "UuidStringer", depth = 3)),
        )
        val r = assertIs<StringerResolution.Resolved<String>>(resolveStringer(scopes, "Uuid", same))
        assertEquals(1, r.distance)
    }

    @Test
    fun `ambiguous reports every candidate`() {
        val scopes = listOf(
            scope(
                Registration("Uuid", "A", depth = 1),
                Registration("Uuid", "B", depth = 1),
                Registration("Uuid", "C", depth = 1),
            ),
        )
        val r = assertIs<StringerResolution.Ambiguous<String>>(resolveStringer(scopes, "Uuid", same))
        assertEquals(3, r.candidates.size)
    }

    @Test
    fun `resolved carries the distance and depth it was found at`() {
        val scopes = listOf(scope(), scope(Registration("Uuid", "A", depth = 2)))
        val r = assertIs<StringerResolution.Resolved<String>>(resolveStringer(scopes, "Uuid", same))
        assertEquals(1, r.distance)
        assertEquals(2, r.depth)
    }
}
