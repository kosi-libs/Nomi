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
import org.kodein.rest.nomi.processor.common.resource.AnalyzedResource
import org.kodein.rest.nomi.processor.common.service.AnalyzedEndpoint
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
            endpoint.body?.let { add(CodeBlock.of("%N = call.%M()", it.name, RECEIVE)) }
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
