package org.kodein.rest.nomi.processor.server

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.Visibility
import org.kodein.rest.nomi.processor.common.service.AnalyzedEndpoint

/** `@ServerEndpointService`'s fully-qualified name, as KSP expects it: dotted. Server-only, so it
 * does not belong in `nomi-processor-common`'s `NomiFq`. */
internal const val SERVER_ENDPOINT_SERVICE_FQ = "org.kodein.rest.nomi.server.ServerEndpointService"
internal const val HIDE_FROM_OPEN_API_FQ = "org.kodein.rest.nomi.server.ServerEndpointService.HideFromOpenApi"

/** A fully validated `@ServerEndpointService` class - never constructed for an invalid one.
 * [endpoints] holds every endpoint of every `@Endpoint.Service` interface it implements, combined
 * (own route collisions already checked across all of them together). [routeName] is the
 * annotation's own `routeName` argument, already validated - the name of the generated
 * `fun Route.<routeName>(service: <Impl>)`. */
public class AnalyzedServerService(
    public val declaration: KSClassDeclaration,
    public val visibility: Visibility,
    public val routeName: String,
    public val endpoints: List<AnalyzedEndpoint>,
    public val withOpenApiSpecs: Boolean = false,
    /** Each endpoint's interface declaration to the override implementing it, so the OpenAPI emitter
     * can read `@ServerEndpointService.HideFromOpenApi` - which sits on the override - and fall back
     * to the override's KDoc when the interface carries none. */
    public val overrides: Map<KSFunctionDeclaration, KSFunctionDeclaration> = emptyMap(),
) {
    public val packageName: String get() = declaration.packageName.asString()
    public val containingFile: KSFile? get() = declaration.containingFile
}

/** [ServerServiceChecks.check]'s successful result: the validated `routeName` alongside the
 * "maximal" `@Endpoint.Service` interfaces the class implements (see [ServerServiceChecks.check]'s
 * own doc for what "maximal" means here). */
public class ServerServiceCheckResult(
    public val routeName: String,
    public val withOpenApiSpecs: Boolean,
    public val services: List<KSClassDeclaration>,
)
