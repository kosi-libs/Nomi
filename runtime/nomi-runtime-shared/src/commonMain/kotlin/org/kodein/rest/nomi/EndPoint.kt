package org.kodein.rest.nomi

import kotlin.reflect.KClass


@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class Endpoint(val method: Method) {

    public enum class Method {
        Get,
        Post,
        Put,
        Patch,
        Delete
    }

    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.CLASS)
    public annotation class Resource(val path: String = "")

    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.CLASS)
    public annotation class Service

    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.VALUE_PARAMETER)
    public annotation class Query(val name: String = "")

    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
    @Repeatable
    public annotation class UseStringer(val type: KClass<*>, val with: KClass<out Stringer<*>>)

    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
    @Repeatable
    public annotation class Stringed(val with: KClass<out Stringer<*>>)
}

// Marker interface
public interface EndpointService

@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class InternalNomiAPI
