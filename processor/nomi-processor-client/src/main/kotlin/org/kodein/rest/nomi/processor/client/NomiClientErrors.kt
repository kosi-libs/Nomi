package org.kodein.rest.nomi.processor.client

/**
 * Every diagnostic message specific to `@ClientEndpointServiceFactory` (SPECS.md § Client factories,
 * and the client-related bullets of § Generated-name collisions). Everything about the
 * `@Endpoint.Service` interfaces it names lives in `nomi-processor-common`'s `NomiErrors`, since the
 * server processor already reads those same diagnostics.
 */
public object NomiClientErrors {

    public fun notAnObject(name: String, kind: String): String =
        "@ClientEndpointServiceFactory '$name' must annotate an object, not a $kind"

    public fun isCompanionObject(name: String): String =
        "@ClientEndpointServiceFactory '$name' must not be a companion object"

    public fun nestedInResource(name: String, resource: String): String =
        "@ClientEndpointServiceFactory '$name' must not be nested inside resource '$resource'"

    public fun serviceClassNotAService(name: String, serviceClass: String): String =
        "@ClientEndpointServiceFactory '$name': serviceClass '$serviceClass' must be an interface annotated @Endpoint.Service"

    public fun nameBlankOrInvalid(name: String, factoryName: String): String =
        "@ClientEndpointServiceFactory '$name': name '$factoryName' must be non-blank and match [A-Za-z_][A-Za-z0-9_]*"

    public fun nameIsHardKeyword(name: String, factoryName: String): String =
        "@ClientEndpointServiceFactory '$name': name '$factoryName' must not be a Kotlin hard keyword"

    public fun serviceClassRepeated(name: String, serviceClass: String): String =
        "@ClientEndpointServiceFactory '$name' registers serviceClass '$serviceClass' more than once"

    public fun nameRepeated(name: String, factoryName: String): String =
        "@ClientEndpointServiceFactory '$name' registers name '$factoryName' more than once"

    public fun nameCollidesWithExistingMember(name: String, factoryName: String): String =
        "@ClientEndpointServiceFactory '$name': generated extension '$factoryName' collides with an existing member of '$name'"

    public fun implNameCollision(a: String, b: String, generated: String): String =
        "'$a' and '$b' both generate the client implementation name '$generated'"

    public fun collidesWithUserDeclaration(generated: String): String =
        "generated client implementation '$generated' collides with an existing declaration in this package"
}
