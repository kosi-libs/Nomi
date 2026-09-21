package org.kodein.rest.nomi.processor.server

import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toKModifier
import com.squareup.kotlinpoet.ksp.writeTo
import org.kodein.rest.nomi.processor.common.multipart.MultipartPart
import org.kodein.rest.nomi.processor.common.multipart.PartPayload
import org.kodein.rest.nomi.processor.common.resource.AnalyzedResource
import org.kodein.rest.nomi.processor.common.service.AnalyzedEndpoint
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
private val DECODE = MemberName("org.kodein.rest.nomi.server", "decode")
private val DECODE_OR_NULL = MemberName("org.kodein.rest.nomi.server", "decodeOrNull")
private val DECODE_REQUIRED = MemberName("org.kodein.rest.nomi.server", "decodeRequired")
private val ENDPOINT_MULTIPART_RECEIVER = ClassName("org.kodein.rest.nomi.server", "EndpointMultipartReceiver")
private val RECEIVE_MULTIPART = MemberName("io.ktor.server.request", "receiveMultipart")
private val AS_ENDPOINT_VALUE = MemberName("org.kodein.rest.nomi.server", "asEndpointValue")
private val HTTP_ERROR = MemberName("org.kodein.rest.nomi.server", "httpError")
private val HTTP_STATUS_CODE = ClassName("io.ktor.http", "HttpStatusCode")
private val PART_PRESENCE = ClassName("org.kodein.rest.nomi", "Endpoint", "MultipartFormData", "PartPresence")

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

        val funSpec = FunSpec.builder(server.routeName)
            .addAnnotation(AnnotationSpec.builder(OPT_IN).addMember("%T::class", INTERNAL_NOMI_API).build())
            .addModifiers(visibility)
            .receiver(ROUTE_TYPE)
            .addParameter("service", implClassName)
            .addCode(renderChildren(buildRouteTree(server.endpoints)))
            .build()

        val fileSpec = FileSpec.builder(server.packageName, fileName)
            .addFileComment("GENERATED - do not edit")
            .addFunction(funSpec)
            .build()

        fileSpec.writeTo(codeGenerator, aggregating = false, originatingKSFiles = listOfNotNull(server.containingFile))
    }

    private fun renderChildren(node: RouteNode): CodeBlock {
        val builder = CodeBlock.builder()
        for (endpoint in node.endpoints) builder.add(renderHandler(endpoint))
        for (child in node.children.values) {
            val path = "/" + child.resource!!.path.segments.joinToString("/")
            builder.beginControlFlow("%M(%S)", ROUTE_FN, path)
            builder.add(renderChildren(child))
            builder.endControlFlow()
        }
        return builder.build()
    }

    private fun renderHandler(endpoint: AnalyzedEndpoint): CodeBlock {
        val builder = CodeBlock.builder()
        builder.beginControlFlow("%M", httpMethodMember(endpoint.method))
        builder.beginControlFlow("call.%M(service)", RESPOND)
        builder.add(renderCall(endpoint))
        builder.add("\n")
        builder.endControlFlow()
        builder.endControlFlow()
        return builder.build()
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
