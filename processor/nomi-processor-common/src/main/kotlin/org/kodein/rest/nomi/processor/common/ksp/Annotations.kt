package org.kodein.rest.nomi.processor.common.ksp

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSValueArgument

// `KSAnnotated.getAnnotationsByType`/`isAnnotationPresent` (@KspExperimental) reflectively load the
// annotation's KClass-typed arguments on the *processor's* classpath (java.lang.reflect.Proxy +
// Class.forName). Nomi annotations name user types (Uuid, UuidStringer, ...) that are deliberately
// absent from the processor's own classpath, so those calls would throw KSTypeNotPresentException.
// Every reader below goes through KSAnnotation.arguments directly instead.

public fun KSAnnotation.fqName(): String? = annotationType.resolve().declaration.qualifiedName?.asString()

/**
 * Every annotation named [fqName] carried by this declaration. Defensively unwraps a synthesized
 * `<fqName>.Container` (the JVM wrapper for a `@Repeatable` annotation applied more than once),
 * in case one ever surfaces for a symbol read from a compiled dependency.
 */
public fun KSAnnotated.annotationsNamed(fqName: String): Sequence<KSAnnotation> =
    annotations.flatMap { ann ->
        when (ann.fqName()) {
            fqName -> sequenceOf(ann)
            "$fqName.Container" ->
                (ann.arguments.firstOrNull()?.value as? List<*>)
                    ?.asSequence()
                    ?.filterIsInstance<KSAnnotation>()
                    ?: emptySequence()
            else -> emptySequence()
        }
    }

public fun KSAnnotated.annotationNamed(fqName: String): KSAnnotation? = annotationsNamed(fqName).firstOrNull()

public fun KSAnnotation.argument(name: String): KSValueArgument? = arguments.firstOrNull { it.name?.asString() == name }

public fun KSAnnotation.stringArg(name: String): String? = argument(name)?.value as? String

public fun KSAnnotation.booleanArg(name: String): Boolean? = argument(name)?.value as? Boolean

public fun KSAnnotation.typeArg(name: String): KSType? = argument(name)?.value as? KSType

/** Reads a `KClass<*>`-typed argument, following through a `typealias` to the class it names. */
public fun KSAnnotation.classArg(name: String): KSClassDeclaration? {
    var decl: KSDeclaration? = typeArg(name)?.declaration
    while (decl is KSTypeAlias) decl = decl.type.resolve().declaration
    return decl as? KSClassDeclaration
}

public fun KSAnnotation.enumArgName(name: String): String? =
    when (val value = argument(name)?.value) {
        is KSType -> value.declaration.simpleName.asString()
        is KSClassDeclaration -> value.simpleName.asString()
        is KSName -> value.getShortName()
        else -> null
    }

public fun KSAnnotation.listArg(name: String): List<*>? = argument(name)?.value as? List<*>

public fun KSAnnotation.annotationArg(name: String): KSAnnotation? = argument(name)?.value as? KSAnnotation

/** Reads the `@Retention` on an annotation-class declaration; Kotlin's own default is RUNTIME. */
public fun KSAnnotated.retention(): AnnotationRetention {
    val ann = annotationNamed("kotlin.annotation.Retention") ?: return AnnotationRetention.RUNTIME
    val name = ann.enumArgName("value") ?: return AnnotationRetention.RUNTIME
    return AnnotationRetention.entries.firstOrNull { it.name == name } ?: AnnotationRetention.RUNTIME
}
