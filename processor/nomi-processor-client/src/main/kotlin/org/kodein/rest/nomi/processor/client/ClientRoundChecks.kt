package org.kodein.rest.nomi.processor.client

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.ksp.fq

/**
 * SPECS.md § Generated-name collisions - the two client-related bullets that need the whole round's
 * registrations at once, unlike [ClientFactoryChecks]'s per-declaration checks. Mirrors
 * `resource/RoundChecks.kt`'s shape.
 */
public class ClientRoundChecks(private val resolver: Resolver, private val reporter: Reporter) {

    public fun check(entries: List<Pair<KSClassDeclaration, ClientFactoryRegistration>>) {
        checkImplNameCollisions(entries)
        checkUserDeclarationCollisions(entries)
    }

    private fun checkImplNameCollisions(entries: List<Pair<KSClassDeclaration, ClientFactoryRegistration>>) {
        for (group in entries.groupBy { (obj, reg) ->
            obj.packageName.asString() to clientImplName(obj.simpleName.asString(), reg.serviceClass.simpleName.asString())
        }.values) {
            if (group.size <= 1) continue
            val (firstObj, firstReg) = group[0]
            val generatedName = clientImplName(firstObj.simpleName.asString(), firstReg.serviceClass.simpleName.asString())
            for (i in 1 until group.size) {
                val (obj, _) = group[i]
                reporter.error(obj, NomiClientErrors.implNameCollision(firstObj.fq(), obj.fq(), generatedName))
            }
        }
    }

    // Only a hit from THIS round's own source files is a genuine collision - not our own previously
    // generated output resurfacing in a later round (see resource/RoundChecks.kt's identical guard).
    private fun checkUserDeclarationCollisions(entries: List<Pair<KSClassDeclaration, ClientFactoryRegistration>>) {
        val userSourceFilePaths = entries.mapNotNull { (obj, _) -> obj.containingFile?.filePath }.toSet()
        for ((obj, reg) in entries) {
            val packageName = obj.packageName.asString()
            val generatedName = clientImplName(obj.simpleName.asString(), reg.serviceClass.simpleName.asString())
            val fqImplName = "$packageName.$generatedName"
            val existing = resolver.getClassDeclarationByName(fqImplName) ?: continue
            if (existing.containingFile?.filePath in userSourceFilePaths) {
                reporter.error(obj, NomiClientErrors.collidesWithUserDeclaration(fqImplName))
            }
        }
    }
}
