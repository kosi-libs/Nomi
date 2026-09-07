package org.kodein.rest.nomi.processor.server

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.Visibility
import org.kodein.rest.nomi.processor.common.service.AnalyzedEndpoint

/** A fully validated `@ServerEndpointService` class - never constructed for an invalid one.
 * [endpoints] holds every endpoint of every `@Endpoint.Service` interface it implements, combined
 * (own route collisions already checked across all of them together). */
public class AnalyzedServerService(
    public val declaration: KSClassDeclaration,
    public val visibility: Visibility,
    public val endpoints: List<AnalyzedEndpoint>,
) {
    public val packageName: String get() = declaration.packageName.asString()
    public val containingFile: KSFile? get() = declaration.containingFile
}
