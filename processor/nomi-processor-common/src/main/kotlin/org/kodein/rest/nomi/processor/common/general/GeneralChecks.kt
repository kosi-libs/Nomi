package org.kodein.rest.nomi.processor.common.general

import com.google.devtools.ksp.isLocal
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import org.kodein.rest.nomi.processor.common.NomiErrors
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.visibilityOrNull

/** SPECS.adoc § General - the subset relevant to resources and Stringers (see the plan's scope note). */
public object GeneralChecks {

    public fun checkReadableDeclaration(d: KSDeclaration, r: Reporter): Boolean {
        var ok = true
        val name = d.fq()

        val visibility = d.visibilityOrNull()
        if (visibility != Visibility.PUBLIC && visibility != Visibility.INTERNAL) {
            r.error(d, NomiErrors.notPublicOrInternal(name)); ok = false
        }
        if (d.isLocal()) {
            r.error(d, NomiErrors.isLocal(name)); ok = false
        }
        if (Modifier.EXPECT in d.modifiers || Modifier.ACTUAL in d.modifiers) {
            r.error(d, NomiErrors.isExpectOrActual(name)); ok = false
        }
        if (Modifier.EXTERNAL in d.modifiers) {
            r.error(d, NomiErrors.isExternal(name)); ok = false
        }
        if (Modifier.INNER in d.modifiers) {
            r.error(d, NomiErrors.isInner(name)); ok = false
        }
        if (d.typeParameters.isNotEmpty()) {
            r.error(d, NomiErrors.hasTypeParameters(name)); ok = false
        }
        return ok
    }

    /** A generated declaration must not reference anything less visible than itself. */
    public fun checkAtLeastAsVisible(used: KSDeclaration, by: KSDeclaration, r: Reporter): Boolean {
        if (rank(used.visibilityOrNull()) < rank(by.visibilityOrNull())) {
            r.error(used, NomiErrors.notVisibleEnough(used.fq(), by.fq()))
            return false
        }
        return true
    }

    /** A use-site target that moves a Nomi annotation off its declaration (`@get:`, `@field:`, ...) is an error. */
    public fun checkUseSiteTarget(a: KSAnnotation, at: KSNode, annotationSimpleName: String, r: Reporter): Boolean {
        val target = a.useSiteTarget ?: return true
        r.error(at, NomiErrors.useSiteTargetMovesAnnotation(annotationSimpleName, target.name.lowercase()))
        return false
    }

    private fun rank(v: Visibility): Int = when (v) {
        Visibility.PUBLIC -> 2
        Visibility.INTERNAL -> 1
        else -> 0
    }
}
