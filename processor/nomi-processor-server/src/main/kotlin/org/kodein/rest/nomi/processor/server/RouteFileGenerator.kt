package org.kodein.rest.nomi.processor.server

import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toKModifier
import com.squareup.kotlinpoet.ksp.writeTo
import org.kodein.rest.nomi.processor.common.ksp.annotationNamed
import org.kodein.rest.nomi.processor.common.multipart.MultipartPart
import org.kodein.rest.nomi.processor.common.multipart.PartPayload
import org.kodein.rest.nomi.processor.common.resource.AnalyzedResource
import org.kodein.rest.nomi.processor.common.service.AnalyzedEndpoint
import org.kodein.rest.nomi.processor.common.service.BinaryParam
import org.kodein.rest.nomi.processor.common.service.BodyParam
import org.kodein.rest.nomi.processor.common.service.MultipartParam
import org.kodein.rest.nomi.processor.common.service.HttpMethod
import org.kodein.rest.nomi.processor.common.service.QueryParam
import org.kodein.rest.nomi.processor.common.stringer.StringerRef

private val INTERNAL_NOMI_API = ClassName("org.kodein.rest.nomi", "InternalNomiAPI")
private val OPT_IN = ClassName("kotlin", "OptIn")
private val ROUTE_TYPE = ClassName("io.ktor.server.routing", "Route")
private val ROUTE_FN = MemberName("io.ktor.server.routing", "route")
private val RECEIVE = MemberName("io.ktor.server.request", "receive")
private val RESPOND = MemberName("org.kodein.rest.nomi.server", "respond")
private val RESPOND_ENDPOINT_BINARY = MemberName("org.kodein.rest.nomi.server", "respondEndpointBinary")
private val DECODE = MemberName("org.kodein.rest.nomi.server", "decode")
private val DECODE_OR_NULL = MemberName("org.kodein.rest.nomi.server", "decodeOrNull")
private val DECODE_REQUIRED = MemberName("org.kodein.rest.nomi.server", "decodeRequired")
private val ENDPOINT_MULTIPART_RECEIVER = ClassName("org.kodein.rest.nomi.server", "EndpointMultipartReceiver")
private val RECEIVE_MULTIPART = MemberName("io.ktor.server.request", "receiveMultipart")
private val AS_ENDPOINT_VALUE = MemberName("org.kodein.rest.nomi.server", "asEndpointValue")
private val HTTP_ERROR = MemberName("org.kodein.rest.nomi.server", "httpError")
private val HTTP_STATUS_CODE = ClassName("io.ktor.http", "HttpStatusCode")
private val PART_PRESENCE = ClassName("org.kodein.rest.nomi", "Endpoint", "MultipartFormData", "PartPresence")
private val RECEIVE_ENDPOINT_BINARY = MemberName("org.kodein.rest.nomi.server", "receiveEndpointBinary")
private val DESCRIBE = MemberName("io.ktor.server.routing.openapi", "describe")
private val HIDE = MemberName("io.ktor.server.routing.openapi", "hide")
private val EXPERIMENTAL_KTOR_API = ClassName("io.ktor.utils.io", "ExperimentalKtorApi")

private fun httpMethodMember(method: HttpMethod): MemberName =
    MemberName("io.ktor.server.routing", method.name.lowercase())

/**
 * Renders one [AnalyzedServerService] into the `fun Route.<routeName>(service: <Impl>)` described
 * by SPECS.adoc § Server processor - one generated file per `@ServerEndpointService` class (unlike
 * the shared processor's per-source-file grouping), since one such class may implement several
 * services at once and always yields exactly one generated function either way.
 */
public class RouteFileGenerator(private val codeGenerator: CodeGenerator) {

    public fun generate(server: AnalyzedServerService) {
        val implClassName = server.declaration.toClassName()
        val fileName = "Nomi${server.declaration.simpleName.asString()}Routes"
        val visibility = server.visibility.toKModifier() ?: KModifier.PUBLIC

        val optIn = AnnotationSpec.builder(OPT_IN).addMember("%T::class", INTERNAL_NOMI_API)
        // describe/hide are @ExperimentalKtorApi, so the opt-in is added only by a service that asked
        // for specs - a service that did not should not inherit an experimental marker it never uses.
        if (server.withOpenApiSpecs) optIn.addMember("%T::class", EXPERIMENTAL_KTOR_API)

        val funSpec = FunSpec.builder(server.routeName)
            .addAnnotation(optIn.build())
            .addModifiers(visibility)
            .receiver(ROUTE_TYPE)
            .addParameter("service", implClassName)
            .addCode(renderChildren(buildRouteTree(server.endpoints), server))
            .build()

        val fileSpec = FileSpec.builder(server.packageName, fileName)
            .addFileComment("GENERATED - do not edit")
            .addFunction(funSpec)
            .build()

        fileSpec.writeTo(codeGenerator, aggregating = false, originatingKSFiles = listOfNotNull(server.containingFile))
    }

    private fun renderChildren(node: RouteNode, server: AnalyzedServerService): CodeBlock {
        val builder = CodeBlock.builder()
        for (endpoint in node.endpoints) builder.add(renderHandler(endpoint, server))
        for (child in node.children.values) {
            val path = "/" + child.resource!!.path.segments.joinToString("/")
            builder.beginControlFlow("%M(%S)", ROUTE_FN, path)
            builder.add(renderChildren(child, server))
            builder.endControlFlow()
        }
        return builder.build()
    }

    private fun renderHandler(endpoint: AnalyzedEndpoint, server: AnalyzedServerService): CodeBlock {
        val builder = CodeBlock.builder()
        builder.beginControlFlow("%M", httpMethodMember(endpoint.method))

        // A streamed body is received before the endpoint runs - ktor allows one receive per call -
        // and closed after it, so a read that outlives the endpoint fails loudly rather than
        // reading a buffer the engine has already recycled.
        val binary = endpoint.payload as? BinaryParam
        if (binary != null) {
            builder.addStatement("val %N = call.%M(service)", binary.name, RECEIVE_ENDPOINT_BINARY)
            builder.beginControlFlow("try")
        }

        // A streamed answer goes out through respondEndpointBinary, which writes the bytes straight to
        // the response channel instead of serializing them, and closes the binary afterwards. The close
        // cannot live here as it does for a received body: this binary does not exist until the block runs.
        builder.beginControlFlow("call.%M(service)", if (endpoint.streamedValue) RESPOND_ENDPOINT_BINARY else RESPOND)
        builder.add(renderCall(endpoint))
        builder.add("\n")
        builder.endControlFlow()

        if (binary != null) {
            builder.nextControlFlow("finally")
            builder.addStatement("%N.close()", binary.name)
            builder.endControlFlow()
        }

        // The method block is closed by hand rather than with endControlFlow, because describe/hide
        // chain onto the Route it returns and beginControlFlow cannot express a chained call - the
        // same reason renderMultipartArg writes its own braces.
        builder.unindent()
        builder.add(renderOpenApi(endpoint, server))
        return builder.build()
    }

    // Computed once per generated file: the ids of a service's endpoints depend on each other, since
    // a collision between two of them is what decides whether the method joins the name.
    private val operationIdCache = mutableMapOf<AnalyzedServerService, Map<AnalyzedEndpoint, String>>()

    private fun operationIds(server: AnalyzedServerService): Map<AnalyzedEndpoint, String> =
        operationIdCache.getOrPut(server) {
            OpenApiDescribeRenderer.operationIds(server.declaration.simpleName.asString(), server.endpoints)
        }

    private fun renderOpenApi(endpoint: AnalyzedEndpoint, server: AnalyzedServerService): CodeBlock {
        if (!server.withOpenApiSpecs) return CodeBlock.of("}\n")

        val override = server.overrides[endpoint.declaration]
        if (override?.annotationNamed(HIDE_FROM_OPEN_API_FQ) != null) {
            return CodeBlock.of("}.%M()\n", HIDE)
        }

        val operationId = operationIds(server).getValue(endpoint)

        val builder = CodeBlock.builder()
        builder.add("}.%M {\n", DESCRIBE).indent()
        builder.add(OpenApiDescribeRenderer.render(endpoint, endpointDoc(endpoint, override), operationId))
        builder.unindent().add("}\n")
        return builder.build()
    }

    /** The endpoint's KDoc as OpenAPI wants it. Read from the interface, where the contract is
     * documented, falling back to the implementation's own when the interface carries none. */
    private fun endpointDoc(endpoint: AnalyzedEndpoint, override: KSFunctionDeclaration?): EndpointDoc? {
        val raw = endpoint.declaration.docString?.takeIf { it.isNotBlank() }
            ?: override?.docString?.takeIf { it.isNotBlank() }
            ?: return null

        val lines = raw.trim().lines().map { it.trim().removePrefix("*").trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val summary = lines.first()
        val description = lines.drop(1).joinToString(" ").takeIf { it.isNotEmpty() }
        return EndpointDoc(summary, description)
    }

    // NomiArticleComments(articleId = decode(UuidStringer, call.parameters["articleId"]!!))
    //     .get(page = decodeOrNull(IntStringer, call.queryParameters["page"]))
    private fun renderCall(endpoint: AnalyzedEndpoint): CodeBlock {
        val builder = CodeBlock.builder()
        builder.add(renderResourceConstruction(endpoint.resource))

        val args = buildList {
            when (val payload = endpoint.payload) {
                null -> Unit
                is BodyParam -> add(CodeBlock.of("%N = call.%M()", payload.name, RECEIVE))
                // Received above, so the handler can close it afterwards.
                is BinaryParam -> add(CodeBlock.of("%N = %N", payload.name, payload.name))
                is MultipartParam -> add(renderMultipartArg(payload))
            }
            endpoint.queries.forEach { add(renderQueryArg(it)) }
        }
        if (args.isEmpty()) {
            builder.add("\n.%N()", endpoint.name)
        } else {
            builder.add("\n.%N(\n", endpoint.name)
            builder.indent()
            for (arg in args) builder.add("%L,\n", arg)
            builder.unindent()
            builder.add(")")
        }
        return builder.build()
    }

    private fun renderResourceConstruction(resource: AnalyzedResource): CodeBlock {
        val implClassName = ClassName(resource.packageName, resource.implSimpleName)
        val params = resource.allParams
        if (params.isEmpty()) return CodeBlock.of("%T", implClassName)

        val builder = CodeBlock.builder()
        builder.add("%T(\n", implClassName)
        builder.indent()
        for (param in params) {
            builder.add(
                "%N = %M(%L, call.parameters[%S]!!),\n",
                param.name, DECODE, stringerCodeBlock(param.stringer), param.name,
            )
        }
        builder.unindent()
        builder.add(")")
        return builder.build()
    }

    // parts = EndpointMultipartReceiver(
    //     service = this,
    //     multipart = call.receiveMultipart(),
    //     parts = mapOf("file" to PartPresence.Required),
    // ) {
    //     when (it.name) {
    //         "file" -> Upload.File(it.asEndpointFile("file"))
    //         else -> httpError(HttpStatusCode.BadRequest, "Unknown part '${'$'}{it.name}'")
    //     }
    // }
    private fun renderMultipartArg(payload: MultipartParam): CodeBlock {
        val builder = CodeBlock.builder()
        builder.add("%N = %T(\n", payload.name, ENDPOINT_MULTIPART_RECEIVER)
        builder.indent()
        builder.add("service = this,\n")
        builder.add("multipart = call.%M(),\n", RECEIVE_MULTIPART)
        builder.add("parts = mapOf(\n")
        builder.indent()
        for (part in payload.multipart.parts) {
            builder.add("%S to %T.%N,\n", part.wireName, PART_PRESENCE, part.presence.name)
        }
        builder.unindent()
        builder.add("),\n")
        builder.unindent()
        // A trailing lambda on an expression being built mid-CodeBlock: literal braces, never
        // beginControlFlow, which would break the enclosing argument list.
        builder.add(") {\n")
        builder.indent()
        builder.add("when (it.name) {\n")
        builder.indent()
        for (part in payload.multipart.parts) {
            builder.add("%S -> %T(%L)\n", part.wireName, part.declaration.toClassName(), renderPartDecode(part))
        }
        builder.add(
            "else -> %M(%T.BadRequest, \"Unknown part '\${it.name}'\")\n",
            HTTP_ERROR, HTTP_STATUS_CODE,
        )
        builder.unindent()
        builder.add("}\n")
        builder.unindent()
        builder.add("}")
        return builder.build()
    }

    private fun renderPartDecode(part: MultipartPart): CodeBlock = when (val payload = part.payload) {
        // asEndpointFile is a PartScope member extension - resolved inside the transform lambda,
        // so it must NOT be emitted as a MemberName (that would import something unusable).
        is PartPayload.File -> CodeBlock.of("it.asEndpointFile(%S)", part.wireName)
        is PartPayload.Value -> CodeBlock.of("it.%M(%S, %L)", AS_ENDPOINT_VALUE, part.wireName, stringerCodeBlock(payload.stringer))
    }

    private fun renderQueryArg(query: QueryParam): CodeBlock =
        if (query.optional) {
            CodeBlock.of("%N = %M(%L, call.queryParameters[%S])", query.parameterName, DECODE_OR_NULL, stringerCodeBlock(query.stringer), query.wireName)
        } else {
            CodeBlock.of(
                "%N = %M(%L, %S, call.queryParameters[%S])",
                query.parameterName, DECODE_REQUIRED, stringerCodeBlock(query.stringer), query.wireName, query.wireName,
            )
        }

    private fun stringerCodeBlock(stringer: StringerRef): CodeBlock = CodeBlock.of("%T", stringer.declaration.toClassName())
}
