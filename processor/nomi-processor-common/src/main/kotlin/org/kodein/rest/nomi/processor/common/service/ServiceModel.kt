package org.kodein.rest.nomi.processor.common.service

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Visibility
import org.kodein.rest.nomi.processor.common.multipart.AnalyzedMultipart
import org.kodein.rest.nomi.processor.common.path.PathSegment
import org.kodein.rest.nomi.processor.common.resource.AnalyzedResource
import org.kodein.rest.nomi.processor.common.stringer.StringerRef

/** An endpoint's HTTP method - `@Endpoint(method)`'s argument, or the endpoint function's own name
 * (`get`, `post`, ...) when the annotation is absent. Constant names match `Endpoint.Method`'s
 * exactly, so an `@Endpoint` annotation's argument name resolves with a plain `valueOf`. */
public enum class HttpMethod { Get, Post, Put, Patch, Delete }

/** What an endpoint takes as its request, in the one unannotated first parameter. SPECS.adoc §
 * Endpoint parameters: "An endpoint declares either an ordinary body or a `Multipart<T>` parameter,
 * never both" - sealed so that both generators' handling of the two is compiler-checked. */
public sealed interface RequestPayload {
    public val declaration: KSValueParameter
    public val name: String
}

/** The request-body parameter of an endpoint (unannotated, always first). */
public class BodyParam(
    override val declaration: KSValueParameter,
    override val name: String,
    public val type: KSType,
) : RequestPayload

/** The `Multipart<T>` parameter of an endpoint, with `T`'s analysed parts. */
public class MultipartParam(
    override val declaration: KSValueParameter,
    override val name: String,
    public val multipart: AnalyzedMultipart,
) : RequestPayload

/** One `@Endpoint.Query` parameter, already resolved. [type] is nullability-stripped; [optional]
 * records whether the *declared* parameter type was nullable (SPECS.adoc § Endpoint parameters:
 * nullable is optional, non-nullable is required). */
public class QueryParam(
    public val declaration: KSValueParameter,
    public val parameterName: String,
    public val wireName: String,
    public val type: KSType,
    public val optional: Boolean,
    public val stringer: StringerRef,
)

/** A fully validated endpoint function - never constructed for an invalid one. [declaringService]
 * is the `@Endpoint.Service` interface that actually declares [declaration], which may differ from
 * the service this endpoint was collected for when it is inherited. */
public class AnalyzedEndpoint(
    public val declaration: KSFunctionDeclaration,
    public val declaringService: KSClassDeclaration,
    public val resource: AnalyzedResource,
    public val method: HttpMethod,
    public val payload: RequestPayload?,
    public val queries: List<QueryParam>,
    public val valueType: KSType,
    public val errorType: KSType,
) {
    public val name: String get() = declaration.simpleName.asString()

    /** The full route path: every resource in [resource]'s chain, root first, concatenated. */
    public val fullPath: List<PathSegment> get() = resource.chain.flatMap { it.path.segments }
}

/** A fully validated `@Endpoint.Service` interface - never constructed for an invalid one.
 * [endpoints] holds the interface's own methods plus those of every `@Endpoint.Service` interface
 * it extends, transitively - source order, deduplicated. */
public class AnalyzedService(
    public val declaration: KSClassDeclaration,
    public val visibility: Visibility,
    public val endpoints: List<AnalyzedEndpoint>,
) {
    public val packageName: String get() = declaration.packageName.asString()
    public val containingFile: KSFile? get() = declaration.containingFile
}

/** [route]'s identity for `(path, method)` collision purposes: a placeholder's own name doesn't
 * matter (two routes differing only in their path parameter's name still collide at the routing
 * layer), only the literal/placeholder shape of each segment does. Used both within one service
 * (SPECS.adoc § Service declarations) and across every service one `@ServerEndpointService` class
 * implements together (SPECS.adoc § Server implementations). */
public fun routeKey(route: List<PathSegment>, method: HttpMethod): Pair<String, HttpMethod> {
    val canonical = route.joinToString("/") { segment -> segment.prefix + (if (segment.parameter != null) "{}" else "") + segment.suffix }
    return canonical to method
}
