package org.kodein.rest.nomi.processor.server

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import org.kodein.rest.nomi.processor.common.NomiFq
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.multipart.MultipartPart
import org.kodein.rest.nomi.processor.common.multipart.PartPayload
import org.kodein.rest.nomi.processor.common.service.AnalyzedEndpoint
import org.kodein.rest.nomi.processor.common.service.BinaryParam
import org.kodein.rest.nomi.processor.common.service.BodyParam
import org.kodein.rest.nomi.processor.common.service.MultipartParam
import org.kodein.rest.nomi.processor.common.stringer.StringerRef

private val JSON_SCHEMA = ClassName("io.ktor.openapi", "JsonSchema")
private val JSON_TYPE = ClassName("io.ktor.openapi", "JsonType")
private val ENCODING = ClassName("io.ktor.openapi", "Encoding")
private val CONTENT_TYPE = ClassName("io.ktor.http", "ContentType")
private val HTTP_STATUS_CODE_CN = ClassName("io.ktor.http", "HttpStatusCode")
private val JSON_SCHEMA_FN = MemberName("io.ktor.openapi", "jsonSchema")

/**
 * Renders the `describe { }` body that documents one endpoint, per SPECS.adoc § Server processor.
 *
 * Everything here is a fact the processor already holds - method and path come from the route tree
 * ktor walks, so only what a route cannot show itself is emitted. That is the whole reason Nomi can
 * describe an endpoint exactly where ktor's own inference has to guess: it never has to read back
 * out of a lambda what it put in.
 */
internal object OpenApiDescribeRenderer {

    fun render(endpoint: AnalyzedEndpoint, doc: EndpointDoc?, operationId: String): CodeBlock {
        val builder = CodeBlock.builder()

        if (doc != null) {
            builder.add("summary = %S\n", doc.summary)
            if (doc.description != null) builder.add("description = %S\n", doc.description)
        }
        builder.add("operationId = %S\n", operationId)

        renderParameters(endpoint)?.let { builder.add(it) }
        renderRequestBody(endpoint)?.let { builder.add(it) }
        builder.add(renderResponses(endpoint))

        return builder.build()
    }

    /**
     * OperationIds for one service, which OpenAPI requires to be unique across the document.
     *
     * The endpoint's own name is not enough - two resources of one service may each have a `get`, as
     * `Tools.Headers` and `Tools.Download` do - so the resource chain is part of it. Where even that
     * collides, which needs two same-named endpoints on one resource under different methods, the
     * method breaks the tie; `(path, method)` uniqueness is already enforced, so this always
     * terminates.
     */
    fun operationIds(service: String, endpoints: List<AnalyzedEndpoint>): Map<AnalyzedEndpoint, String> {
        val base = endpoints.associateWith { endpoint ->
            val chain = endpoint.resource.chain.joinToString("") { it.declaration.simpleName.asString() }
            "${service}_${chain}_${endpoint.name}"
        }
        val duplicated = base.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return base.mapValues { (endpoint, id) ->
            if (id in duplicated) "${id}_${endpoint.method.name}" else id
        }
    }

    // parameters {
    //   path("articleId") { required = true; schema = JsonSchema(type = JsonType.STRING) }
    //   query("page") { required = false; schema = JsonSchema(type = JsonType.INTEGER) }
    // }
    private fun renderParameters(endpoint: AnalyzedEndpoint): CodeBlock? {
        val paths = endpoint.resource.allParams
        if (paths.isEmpty() && endpoint.queries.isEmpty()) return null

        val builder = CodeBlock.builder()
        builder.add("parameters {\n").indent()
        for (param in paths) {
            // A path parameter is always required: it is part of the route that matched.
            builder.add("path(%S) {\n", param.name).indent()
            builder.add("required = true\n")
            builder.add("schema = %L\n", parameterSchema(param.stringer))
            builder.unindent().add("}\n")
        }
        for (query in endpoint.queries) {
            builder.add("query(%S) {\n", query.wireName).indent()
            // Nomi's own rule, exactly: a nullable query parameter is optional, a non-nullable one required.
            builder.add("required = %L\n", !query.optional)
            builder.add("schema = %L\n", parameterSchema(query.stringer))
            builder.unindent().add("}\n")
        }
        builder.unindent().add("}\n")
        return builder.build()
    }

    /**
     * What a parameter looks like *on the wire*, which is what a spec describes.
     *
     * Only the `Int` and `Long` builtins are numbers there. Everything else is a string, including
     * `Boolean`: its builtin `Stringer` encodes `"1"`/`"0"`, so `type: boolean` would be a lie. A
     * declared `Stringer` produces arbitrary text and says nothing about its shape, so `type: string`
     * is all Nomi can honestly claim.
     */
    private fun parameterSchema(stringer: StringerRef): CodeBlock {
        val jsonType = when {
            stringer !is StringerRef.Builtin -> "STRING"
            stringer.declaration.fq() == NomiFq.INT_STRINGER -> "INTEGER"
            stringer.declaration.fq() == NomiFq.LONG_STRINGER -> "INTEGER"
            else -> "STRING"
        }
        return CodeBlock.of("%T(type = %T.%N)", JSON_SCHEMA, JSON_TYPE, jsonType)
    }

    private fun renderRequestBody(endpoint: AnalyzedEndpoint): CodeBlock? {
        val builder = CodeBlock.builder()
        when (val payload = endpoint.payload) {
            null -> return null
            is BodyParam -> {
                builder.add("requestBody {\n").indent()
                builder.add("required = true\n")
                builder.add("%T.Application.Json {\n", CONTENT_TYPE).indent()
                builder.add("schema = %M<%T>()\n", JSON_SCHEMA_FN, payload.type.makeNotNullable().toTypeName())
                builder.unindent().add("}\n")
                builder.unindent().add("}\n")
            }
            is BinaryParam -> {
                builder.add("requestBody {\n").indent()
                builder.add("required = true\n")
                builder.add(binaryMediaType())
                builder.unindent().add("}\n")
            }
            is MultipartParam -> {
                builder.add("requestBody {\n").indent()
                builder.add("required = true\n")
                builder.add(multipartMediaType(payload))
                builder.unindent().add("}\n")
            }
        }
        return builder.build()
    }

    /** A streamed body or answer. Its real content type is an instance property of a value built per
     * call, so there is no object to reference from here - `octet-stream` is the honest ceiling. */
    private fun binaryMediaType(): CodeBlock =
        CodeBlock.builder()
            .add("%T.Application.OctetStream {\n", CONTENT_TYPE).indent()
            .add("schema = %T(type = %T.STRING, format = %S)\n", JSON_SCHEMA, JSON_TYPE, "binary")
            .unindent().add("}\n")
            .build()

    // ContentType.MultiPart.FormData {
    //   schema = JsonSchema(type = JsonType.OBJECT, required = listOf("file"), properties = mapOf(...))
    //   encoding("article", Encoding(contentType = ArticleContentJsonStringer.contentType))
    // }
    private fun multipartMediaType(payload: MultipartParam): CodeBlock {
        val parts = payload.multipart.parts
        val builder = CodeBlock.builder()
        builder.add("%T.MultiPart.FormData {\n", CONTENT_TYPE).indent()

        builder.add("schema = %T(\n", JSON_SCHEMA).indent()
        builder.add("type = %T.OBJECT,\n", JSON_TYPE)
        val required = parts.filter { it.presence.isRequired }
        if (required.isNotEmpty()) {
            builder.add("required = listOf(%L),\n", required.joinToString(", ") { "\"${it.wireName}\"" })
        }
        builder.add("properties = mapOf(\n").indent()
        for (part in parts) builder.add("%S to %T.Value(%L),\n", part.wireName, REFERENCE_OR, partSchema(part))
        builder.unindent().add("),\n")
        builder.unindent().add(")\n")

        // OpenAPI's encoding map is where a part's own media type belongs. The processor never learns
        // what a Stringer's contentType *is* - it is a property with a default body - but it does not
        // need to: this is runtime code and a Stringer is an object, so the value is read there.
        for (part in parts) {
            val contentType = when (val partPayload = part.payload) {
                is PartPayload.File -> CodeBlock.of("%T.Application.OctetStream", CONTENT_TYPE)
                is PartPayload.Value ->
                    CodeBlock.of("%T.contentType", partPayload.stringer.declaration.toClassName())
            }
            builder.add("encoding(%S, %T(contentType = %L))\n", part.wireName, ENCODING, contentType)
        }

        builder.unindent().add("}\n")
        return builder.build()
    }

    private fun partSchema(part: MultipartPart): CodeBlock {
        val one = when (part.payload) {
            is PartPayload.File -> CodeBlock.of("%T(type = %T.STRING, format = %S)", JSON_SCHEMA, JSON_TYPE, "binary")
            is PartPayload.Value -> CodeBlock.of("%M<%T>()", JSON_SCHEMA_FN, part.payloadType.makeNotNullable().toTypeName())
        }
        // A repeatable part is an array of whatever one occurrence carries.
        return if (!part.presence.isRepeated) one
        else CodeBlock.of(
            "%T(type = %T.ARRAY, items = %T.Value(%L))",
            JSON_SCHEMA, JSON_TYPE, REFERENCE_OR, one,
        )
    }

    // responses {
    //   HttpStatusCode.OK { schema = jsonSchema<ArticleContent>() }
    //   HttpStatusCode.UnprocessableEntity { schema = jsonSchema<ArticleCreationFailure>() }
    //   HttpStatusCode.BadRequest { description = "..." }
    // }
    private fun renderResponses(endpoint: AnalyzedEndpoint): CodeBlock {
        val builder = CodeBlock.builder()
        builder.add("responses {\n").indent()

        val valueFq = endpoint.valueType.declaration.fq()
        when {
            // README.adoc § Wire protocol: Unit and null are both the empty success.
            valueFq == "kotlin.Unit" -> {
                builder.add("%T.NoContent {\n", HTTP_STATUS_CODE_CN).indent()
                builder.add("description = %S\n", "Success, with no content.")
                builder.unindent().add("}\n")
            }
            endpoint.streamedValue -> {
                builder.add("%T.OK {\n", HTTP_STATUS_CODE_CN).indent()
                builder.add(binaryMediaType())
                builder.unindent().add("}\n")
            }
            else -> {
                builder.add("%T.OK {\n", HTTP_STATUS_CODE_CN).indent()
                builder.add("schema = %M<%T>()\n", JSON_SCHEMA_FN, endpoint.valueType.makeNotNullable().toTypeName())
                builder.unindent().add("}\n")
                // A nullable success answers 204 instead, so it is a second documented outcome.
                if (endpoint.valueType.isMarkedNullable) {
                    builder.add("%T.NoContent {\n", HTTP_STATUS_CODE_CN).indent()
                    builder.add("description = %S\n", "Success, with no content.")
                    builder.unindent().add("}\n")
                }
            }
        }

        // NoFail says this endpoint has no business failure, so there is no 422 to document at all.
        if (endpoint.errorType.declaration.fq() != NomiFq.NO_FAIL) {
            builder.add("%T.UnprocessableEntity {\n", HTTP_STATUS_CODE_CN).indent()
            builder.add("description = %S\n", "A business failure.")
            builder.add("schema = %M<%T>()\n", JSON_SCHEMA_FN, endpoint.errorType.makeNotNullable().toTypeName())
            builder.unindent().add("}\n")
        }

        // The only two sources of a 400 are a Stringer that fails to decode and a required query
        // parameter that is absent - both of which Nomi knows statically.
        if (endpoint.resource.allParams.isNotEmpty() || endpoint.queries.isNotEmpty()) {
            builder.add("%T.BadRequest {\n", HTTP_STATUS_CODE_CN).indent()
            builder.add("description = %S\n", "A path or query parameter was missing or could not be decoded.")
            builder.unindent().add("}\n")
        }

        builder.unindent().add("}\n")
        return builder.build()
    }
}

private val REFERENCE_OR = ClassName("io.ktor.openapi", "ReferenceOr")

/** An endpoint's KDoc, split the way OpenAPI wants it: a one-line summary, then the rest. */
internal class EndpointDoc(val summary: String, val description: String?)
