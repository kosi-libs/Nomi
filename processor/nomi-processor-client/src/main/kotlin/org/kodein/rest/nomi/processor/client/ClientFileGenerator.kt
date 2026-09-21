package org.kodein.rest.nomi.processor.client

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toKModifier
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import org.kodein.rest.nomi.processor.common.ksp.visibilityOrNull
import org.kodein.rest.nomi.processor.common.path.PathSegment
import org.kodein.rest.nomi.processor.common.resource.PathParam
import org.kodein.rest.nomi.processor.common.multipart.MultipartPart
import org.kodein.rest.nomi.processor.common.multipart.PartPayload
import org.kodein.rest.nomi.processor.common.service.AnalyzedEndpoint
import org.kodein.rest.nomi.processor.common.service.BinaryParam
import org.kodein.rest.nomi.processor.common.service.BodyParam
import org.kodein.rest.nomi.processor.common.service.MultipartParam
import org.kodein.rest.nomi.processor.common.service.AnalyzedService
import org.kodein.rest.nomi.processor.common.service.HttpMethod
import org.kodein.rest.nomi.processor.common.service.QueryParam

private val INTERNAL_NOMI_API = ClassName("org.kodein.rest.nomi", "InternalNomiAPI")
private val OPT_IN = ClassName("kotlin", "OptIn")
private val EITHER = ClassName("org.kodein.rest.nomi", "Either")
private val HTTP_CLIENT = ClassName("io.ktor.client", "HttpClient")
private val HTTP_REQUEST_BUILDER = ClassName("io.ktor.client.request", "HttpRequestBuilder")
private val SERVICE_CALLER = ClassName("org.kodein.rest.nomi.client", "ServiceCaller")
private val NOMI_SERVICE_CALLER = ClassName("org.kodein.rest.nomi.client", "NomiServiceCaller")
private val NOMI_CLIENT_ENDPOINT_SERVICE = ClassName("org.kodein.rest.nomi.client", "NomiClientEndpointService")
private val HTTP_METHOD_COMPANION = ClassName("io.ktor.http", "HttpMethod", "Companion")
private val CALL_MEMBER = MemberName("org.kodein.rest.nomi.client", "call")
private val APPEND_PARAMETER_MEMBER = MemberName("org.kodein.rest.nomi.client", "appendParameter")
private val SET_BODY_MEMBER = MemberName("io.ktor.client.request", "setBody")
private val MULTIPART = ClassName("org.kodein.rest.nomi", "EndpointService", "Multipart")
private val MULTI_PART_FORM_DATA_CONTENT = ClassName("io.ktor.client.request.forms", "MultiPartFormDataContent")
private val FORM_DATA = MemberName("io.ktor.client.request.forms", "formData")
private val FOR_EACH_PART = MemberName("org.kodein.rest.nomi", "forEachPart")
private val APPEND_ENDPOINT_FILE = MemberName("org.kodein.rest.nomi.client", "appendEndpointFile")
private val APPEND_ENDPOINT_ITEM = MemberName("org.kodein.rest.nomi.client", "appendEndpointItem")
private val ENDPOINT_BINARY = ClassName("org.kodein.rest.nomi", "EndpointBinary")
private val SET_ENDPOINT_BINARY_BODY = MemberName("org.kodein.rest.nomi.client", "setEndpointBinaryBody")
private val BUILD_REQUEST_TYPE = LambdaTypeName.get(receiver = HTTP_REQUEST_BUILDER, returnType = UNIT)

private fun httpMethodMember(method: HttpMethod): MemberName = MemberName(HTTP_METHOD_COMPANION, method.name)

/**
 * Renders one `(object, registration, service)` triple into the private implementation + public
 * extension function described by SPECS.adoc § Client processor - one generated file per
 * `@ClientEndpointServiceFactory` registration (an object may carry several), since each yields
 * exactly one client implementation and one extension function either way.
 */
public class ClientFileGenerator(private val codeGenerator: CodeGenerator) {

    public fun generate(obj: KSClassDeclaration, registration: ClientFactoryRegistration, service: AnalyzedService) {
        val objectClassName = obj.toClassName()
        val serviceClassName = registration.serviceClass.toClassName()
        val implSimpleName = clientImplName(obj.simpleName.asString(), registration.serviceClass.simpleName.asString())
        val extensionVisibility = obj.visibilityOrNull().toKModifier() ?: KModifier.PUBLIC

        val callExpr = CodeBlock.builder()
            .add("%T(\n", NOMI_SERVICE_CALLER)
            .indent()
            .add("client = client,\n")
            .add("service = ::%N,\n", implSimpleName)
            .unindent()
            .add(")")
            .build()

        val extensionFun = FunSpec.builder(registration.name)
            .addAnnotation(AnnotationSpec.builder(OPT_IN).addMember("%T::class", INTERNAL_NOMI_API).build())
            .addModifiers(extensionVisibility)
            .receiver(objectClassName)
            .addParameter("client", HTTP_CLIENT)
            .returns(SERVICE_CALLER.parameterizedBy(serviceClassName))
            .addStatement("return %L", callExpr)
            .build()

        val fileSpec = FileSpec.builder(obj.packageName.asString(), implSimpleName)
            .addFileComment("GENERATED - do not edit")
            .addType(buildImplType(implSimpleName, serviceClassName, service))
            .addFunction(extensionFun)
            .build()

        fileSpec.writeTo(codeGenerator, aggregating = false, originatingKSFiles = listOfNotNull(obj.containingFile))
    }

    private fun buildImplType(implSimpleName: String, serviceClassName: ClassName, service: AnalyzedService): TypeSpec {
        val constructor = FunSpec.constructorBuilder()
            .addParameter("client", HTTP_CLIENT)
            .addParameter("buildRequest", BUILD_REQUEST_TYPE)
            .build()

        return TypeSpec.classBuilder(implSimpleName)
            .addModifiers(KModifier.PRIVATE)
            .addAnnotation(AnnotationSpec.builder(OPT_IN).addMember("%T::class", INTERNAL_NOMI_API).build())
            .addSuperinterface(serviceClassName)
            .addSuperinterface(NOMI_CLIENT_ENDPOINT_SERVICE)
            .primaryConstructor(constructor)
            .addProperty(PropertySpec.builder("client", HTTP_CLIENT).initializer("client").build())
            .addProperty(
                PropertySpec.builder("buildRequest", BUILD_REQUEST_TYPE)
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer("buildRequest")
                    .build(),
            )
            .apply { service.endpoints.forEach { addFunction(buildEndpointFun(it)) } }
            .build()
    }

    private fun buildEndpointFun(endpoint: AnalyzedEndpoint): FunSpec {
        val builder = FunSpec.builder(endpoint.name)
            .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
            .receiver(endpoint.resource.declaration.toClassName())
            .returns(EITHER.parameterizedBy(endpoint.valueType.toTypeName(), endpoint.errorType.toTypeName()))

        when (val payload = endpoint.payload) {
            null -> Unit
            is BodyParam -> builder.addParameter(payload.name, payload.type.toTypeName())
            is BinaryParam -> builder.addParameter(payload.name, ENDPOINT_BINARY)
            is MultipartParam -> builder.addParameter(
                payload.name,
                MULTIPART.parameterizedBy(payload.multipart.declaration.toClassName()),
            )
        }
        for (query in endpoint.queries) {
            builder.addParameter(query.parameterName, query.type.toTypeName().copy(nullable = query.optional))
        }

        builder.addStatement("return %L", buildCallExpression(endpoint))
        return builder.build()
    }

    // client.call(
    //     pathSegments = listOf(
    //         "article",
    //         UuidStringer.encode(articleId),
    //         "comments",
    //     ),
    //     method = Get,
    // ) {
    //     appendParameter("page", page, IntStringer)
    // }
    private fun buildCallExpression(endpoint: AnalyzedEndpoint): CodeBlock {
        val paramsByName = endpoint.resource.allParams.associateBy { it.name }

        val builder = CodeBlock.builder()
        builder.add("client.%M(\n", CALL_MEMBER)
        builder.indent()
        builder.add("pathSegments = listOf(\n")
        builder.indent()
        for (segment in endpoint.fullPath) builder.add("%L,\n", pathSegmentExpr(segment, paramsByName))
        builder.unindent()
        builder.add("),\n")
        builder.add("method = %M,\n", httpMethodMember(endpoint.method))
        builder.unindent()
        builder.add(")")

        val blockArgs = buildList {
            when (val payload = endpoint.payload) {
                null -> Unit
                is BodyParam -> add(CodeBlock.of("%M(%N)", SET_BODY_MEMBER, payload.name))
                is BinaryParam -> add(CodeBlock.of("%M(%N)", SET_ENDPOINT_BINARY_BODY, payload.name))
                is MultipartParam -> add(renderMultipartBody(payload))
            }
            endpoint.queries.forEach { add(renderAppendParameter(it)) }
        }
        if (blockArgs.isNotEmpty()) {
            builder.add(" {\n")
            builder.indent()
            for (arg in blockArgs) builder.add("%L\n", arg)
            builder.unindent()
            builder.add("}")
        }

        return builder.build()
    }

    // SPECS.adoc § Client processor: "A resource path that mixes literal text and a parameter inside a
    // single segment (such as p:{postId}) is rendered as the concatenation of that literal text and
    // the encoded parameter, in a single path segment."
    private fun pathSegmentExpr(segment: PathSegment, paramsByName: Map<String, PathParam>): CodeBlock {
        val parameterName = segment.parameter ?: return CodeBlock.of("%S", segment.prefix)
        val param = paramsByName.getValue(parameterName)
        val encoded = CodeBlock.of("%T.encode(%N)", param.stringer.declaration.toClassName(), param.name)
        return if (segment.prefix.isEmpty() && segment.suffix.isEmpty()) encoded
        else CodeBlock.of("%S + %L + %S", segment.prefix, encoded, segment.suffix)
    }

    // setBody(
    //     MultiPartFormDataContent(
    //         formData {
    //             parts.forEachPart { part ->
    //                 when (part) {
    //                     is Upload.File -> appendEndpointFile("file", part.file)
    //                 }
    //             }
    //         }
    //     )
    // )
    private fun renderMultipartBody(payload: MultipartParam): CodeBlock {
        val builder = CodeBlock.builder()
        builder.add("%M(\n", SET_BODY_MEMBER)
        builder.indent()
        builder.add("%T(\n", MULTI_PART_FORM_DATA_CONTENT)
        builder.indent()
        builder.add("%M {\n", FORM_DATA)
        builder.indent()
        builder.add("%N.%M { part ->\n", payload.name, FOR_EACH_PART)
        builder.indent()
        // Exhaustive over the sealed part type, so no `else` - SPECS.adoc § Client processor.
        builder.add("when (part) {\n")
        builder.indent()
        for (part in payload.multipart.parts) {
            builder.add("is %T -> %L\n", part.declaration.toClassName(), renderPartAppend(part))
        }
        builder.unindent()
        builder.add("}\n")
        builder.unindent()
        builder.add("}\n")
        builder.unindent()
        builder.add("}\n")
        builder.unindent()
        builder.add(")\n")
        builder.unindent()
        builder.add(")")
        return builder.build()
    }

    private fun renderPartAppend(part: MultipartPart): CodeBlock {
        val property = part.property.simpleName.asString()
        return when (val payload = part.payload) {
            is PartPayload.File -> CodeBlock.of("%M(%S, part.%N)", APPEND_ENDPOINT_FILE, part.wireName, property)
            is PartPayload.Value -> CodeBlock.of(
                "%M(%S, part.%N, %T)",
                APPEND_ENDPOINT_ITEM, part.wireName, property, payload.stringer.declaration.toClassName(),
            )
        }
    }

    private fun renderAppendParameter(query: QueryParam): CodeBlock =
        CodeBlock.of(
            "%M(%S, %N, %T)",
            APPEND_PARAMETER_MEMBER, query.wireName, query.parameterName, query.stringer.declaration.toClassName(),
        )
}
