package org.kodein.rest.nomi.processor.common.resource

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver
import org.kodein.rest.nomi.processor.common.NomiErrors
import org.kodein.rest.nomi.processor.common.Reporter

/**
 * SPECS.md § Generated-name collisions (the resource-impl and factory-function rules) - needs the
 * whole round's resources at once, unlike the per-declaration checks in [ResourceChecks].
 */
public class RoundChecks(private val resolver: Resolver, private val reporter: Reporter) {

    public fun check(resources: List<AnalyzedResource>) {
        checkImplNameCollisions(resources)
        checkFactoryCollisions(resources)
        checkUserDeclarationCollisions(resources)
        checkFileNameCollisions(resources)
    }

    private fun checkImplNameCollisions(resources: List<AnalyzedResource>) {
        for (group in resources.groupBy { it.packageName to it.implSimpleName }.values) {
            if (group.size <= 1) continue
            for (i in 1 until group.size) {
                reporter.error(
                    group[i].declaration,
                    NomiErrors.implNameCollision(group[0].declaration.qualifiedName?.asString() ?: "?", group[i].declaration.qualifiedName?.asString() ?: "?", group[i].implSimpleName),
                )
            }
        }
    }

    private fun checkFactoryCollisions(resources: List<AnalyzedResource>) {
        for (group in resources.groupBy { Triple(it.packageName, factoryKeyOf(it).receiverFqName, factoryKeyOf(it).name) }.values) {
            if (group.size <= 1) continue
            val key = factoryKeyOf(group[0])
            for (i in 1 until group.size) {
                reporter.error(group[i].declaration, NomiErrors.factoryCollision(key.receiverFqName, key.name))
            }
        }
    }

    // Only checks the generated impl class against an existing resolvable declaration; a
    // collision between a generated factory and an existing top-level function of the same name
    // needs matching by receiver type too and is left for when the client/server processors,
    // which actually read such declarations, extend this check.
    private fun checkUserDeclarationCollisions(resources: List<AnalyzedResource>) {
        // A hit is only a genuine collision when it lives in one of THIS round's own user source
        // files - not our own previously-generated output resurfacing in a later round (which
        // lives under KSP's generated-sources directory, a different file path even if the
        // *name* happens to coincide, e.g. two files both named "endpoints.kt" cannot both be a
        // round's user sources, so comparing file *paths* rather than names is what's load-bearing
        // here, unlike checkFileNameCollisions below which is deliberately name-based).
        val userSourceFilePaths = resources.mapNotNull { it.containingFile?.filePath }.toSet()
        for (resource in resources) {
            val fqImplName = "${resource.packageName}.${resource.implSimpleName}"
            val existing = resolver.getClassDeclarationByName(fqImplName) ?: continue
            if (existing.containingFile?.filePath in userSourceFilePaths) {
                reporter.error(resource.declaration, NomiErrors.collidesWithUserDeclaration(fqImplName))
            }
        }
    }

    private fun checkFileNameCollisions(resources: List<AnalyzedResource>) {
        val files = resources.mapNotNull { it.containingFile }.distinctBy { it.filePath }
        for (group in files.groupBy { it.packageName.asString() to generatedResourcesFileName(it.fileName) }.values) {
            if (group.size <= 1) continue
            val generatedName = generatedResourcesFileName(group[0].fileName)
            for (i in 1 until group.size) {
                reporter.error(null, NomiErrors.fileNameCollision(group[0].fileName, group[i].fileName, generatedName))
            }
        }
    }
}
