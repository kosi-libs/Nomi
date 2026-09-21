package org.kodein.rest.nomi.processor.client

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toKModifier
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import org.kodein.rest.nomi.processor.common.ksp.enclosingChain
import org.kodein.rest.nomi.processor.common.ksp.visibilityOrNull
import org.kodein.rest.nomi.processor.common.multipart.AnalyzedMultipart
import org.kodein.rest.nomi.processor.common.multipart.MultipartPart
import org.kodein.rest.nomi.processor.common.multipart.PartPresence
import org.kodein.rest.nomi.processor.common.resource.generatedMultipartFileName
import org.kodein.rest.nomi.processor.common.resource.multipartBuilderName

private val INTERNAL_NOMI_API = ClassName("org.kodein.rest.nomi", "InternalNomiAPI")
private val OPT_IN = ClassName("kotlin", "OptIn")
private val MULTIPART = ClassName("org.kodein.rest.nomi", "EndpointService", "Multipart")
private val LIST_MULTIPART = ClassName("org.kodein.rest.nomi.client", "ListMultipart")

/**
 * Generates the multipart builders of one `@ClientEndpointServiceFactory` object, into a single
 * `Nomi<Object>Multipart.kt`.
 *
 * SPECS.adoc § Client processor: builders are collected per annotated object, by part type - so the
 * caller hands over the already-deduplicated set, and two objects reaching one part type each get
 * their own builder here.
 */
public class MultipartBuilderGenerator(private val codeGenerator: CodeGenerator) {

    public fun generate(obj: KSClassDeclaration, multiparts: List<AnalyzedMultipart>) {
        if (multiparts.isEmpty()) return

        val objectClassName = obj.toClassName()
        val visibility = obj.visibilityOrNull().toKModifier() ?: KModifier.PUBLIC

        val fileSpec = FileSpec.builder(obj.packageName.asString(), generatedMultipartFileName(obj.simpleName.asString()))
            .addFileComment("GENERATED - do not edit")
            .apply {
                for (multipart in multiparts) addFunction(buildBuilder(objectClassName, visibility, multipart))
            }
            .build()

        fileSpec.writeTo(codeGenerator, aggregating = false, originatingKSFiles = listOfNotNull(obj.containingFile))
    }

    private fun buildBuilder(
        objectClassName: ClassName,
        visibility: KModifier,
        multipart: AnalyzedMultipart,
    ): FunSpec {
        val partTypeName = multipart.declaration.toClassName()
        val builder = FunSpec.builder(builderNameOf(multipart.declaration))
            .addAnnotation(AnnotationSpec.builder(OPT_IN).addMember("%T::class", INTERNAL_NOMI_API).build())
            .addModifiers(visibility)
            .receiver(objectClassName)
            .returns(MULTIPART.parameterizedBy(partTypeName))

        for (part in multipart.parts) builder.addParameter(parameterOf(part))

        val body = CodeBlock.builder()
        body.add("%T(\n", LIST_MULTIPART)
        body.indent()
        body.add("buildList {\n")
        body.indent()
        for (part in multipart.parts) body.add(renderAdd(part))
        body.unindent()
        body.add("}\n")
        body.unindent()
        body.add(")")

        return builder.addStatement("return %L", body.build()).build()
    }

    private fun parameterOf(part: MultipartPart): ParameterSpec {
        val payload: TypeName = part.payloadType.toTypeName()
        return when (part.presence) {
            PartPresence.Required -> ParameterSpec.builder(part.parameterName, payload).build()
            PartPresence.Optional -> ParameterSpec.builder(part.parameterName, payload.copy(nullable = true))
                .defaultValue("null")
                .build()
            PartPresence.RepeatedRequired -> ParameterSpec.builder(part.parameterName, LIST.parameterizedBy(payload)).build()
            PartPresence.RepeatedOptional -> ParameterSpec.builder(part.parameterName, LIST.parameterizedBy(payload))
                .defaultValue("emptyList()")
                .build()
        }
    }

    private fun renderAdd(part: MultipartPart): CodeBlock {
        val subtype = part.declaration.toClassName()
        val name = part.parameterName
        return when (part.presence) {
            PartPresence.Required -> CodeBlock.of("add(%T(%N))\n", subtype, name)
            PartPresence.Optional -> CodeBlock.builder()
                .beginControlFlow("if (%N != null)", name)
                .add("add(%T(%N))\n", subtype, name)
                .endControlFlow()
                .build()
            PartPresence.RepeatedRequired -> CodeBlock.builder()
                .add("require(%N.isNotEmpty()) { %S }\n", name, "$name is required")
                .add("addAll(%N.map { %T(it) })\n", name, subtype)
                .build()
            PartPresence.RepeatedOptional -> CodeBlock.of("addAll(%N.map { %T(it) })\n", name, subtype)
        }
    }
}

/** SPECS.adoc § Generated-name collisions: `multipart` + the part type's simple names, outermost first. */
public fun builderNameOf(partType: KSClassDeclaration): String =
    multipartBuilderName(partType.enclosingChain().map { it.simpleName.asString() } + partType.simpleName.asString())
