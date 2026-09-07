package org.kodein.rest.nomi.processor.shared

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ContextParameter
import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toKModifier
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import org.kodein.rest.nomi.processor.common.resource.AnalyzedResource
import org.kodein.rest.nomi.processor.common.resource.generatedResourcesFileName

private val INTERNAL_NOMI_API = ClassName("org.kodein.rest.nomi", "InternalNomiAPI")
private val ENDPOINT_SERVICE = ClassName("org.kodein.rest.nomi", "EndpointService")
private val OPT_IN = ClassName("kotlin", "OptIn")

/**
 * Renders each [AnalyzedResource] into the `Nomi<Chain>` implementation + factory function pair
 * described by SPECS.md § Shared processor - one generated file per source file, implementations
 * first then factories, matching the hand-written golden file's grouping.
 */
@OptIn(ExperimentalKotlinPoetApi::class)
public class ResourceFileGenerator(private val codeGenerator: CodeGenerator) {

    public fun generate(file: KSFile, resources: List<AnalyzedResource>) {
        if (resources.isEmpty()) return

        val fileSpec = FileSpec.builder(file.packageName.asString(), generatedResourcesFileName(file.fileName))
            .addFileComment("GENERATED - do not edit")
            .apply { resources.forEach { addType(implType(it)) } }
            .apply { resources.forEach { addFunction(factory(it)) } }
            .build()

        fileSpec.writeTo(codeGenerator, aggregating = false, originatingKSFiles = listOf(file))
    }

    private fun implType(resource: AnalyzedResource): TypeSpec {
        val params = resource.allParams
        val visibility = resource.visibility.toKModifier() ?: KModifier.PUBLIC
        val builder = if (params.isEmpty()) TypeSpec.objectBuilder(resource.implSimpleName) else TypeSpec.classBuilder(resource.implSimpleName)

        builder
            .addModifiers(KModifier.DATA, visibility)
            .addAnnotation(INTERNAL_NOMI_API)
            .addSuperinterface(resource.declaration.toClassName())

        if (params.isNotEmpty()) {
            val constructor = FunSpec.constructorBuilder()
            for (param in params) constructor.addParameter(param.name, param.type.toTypeName())
            builder.primaryConstructor(constructor.build())

            for (param in params) {
                builder.addProperty(
                    PropertySpec.builder(param.name, param.type.toTypeName())
                        .addModifiers(KModifier.OVERRIDE)
                        .initializer(param.name)
                        .build(),
                )
            }
        }

        return builder.build()
    }

    private fun factory(resource: AnalyzedResource): FunSpec {
        val visibility = resource.visibility.toKModifier() ?: KModifier.PUBLIC
        val implClassName = ClassName(resource.packageName, resource.implSimpleName)

        val builder = FunSpec.builder(resource.factoryName)
            .addAnnotation(AnnotationSpec.builder(OPT_IN).addMember("%T::class", INTERNAL_NOMI_API).build())
            .contextParameter(ContextParameter(ENDPOINT_SERVICE))
            .addModifiers(visibility)
            .returns(resource.declaration.toClassName())

        resource.parent?.let { builder.receiver(it.declaration.toClassName()) }
        for (param in resource.ownParams) builder.addParameter(param.name, param.type.toTypeName())

        val allParamNames = resource.allParams.map { it.name }
        if (allParamNames.isEmpty()) {
            builder.addStatement("return %T", implClassName)
        } else {
            val placeholders = allParamNames.joinToString(", ") { "%N" }
            builder.addStatement("return %T($placeholders)", implClassName, *allParamNames.toTypedArray())
        }

        return builder.build()
    }
}
