package org.kodein.rest.nomi.processor.server

/**
 * Every diagnostic message specific to `@ServerEndpointService` (SPECS.adoc § Server implementations).
 * Everything about the `@Endpoint.Service` interfaces it implements lives in `nomi-processor-common`'s
 * `NomiErrors`, since the client processor needs those same diagnostics too.
 */
public object NomiServerErrors {

    public fun notConcreteClassOrObject(name: String, kind: String): String =
        "@ServerEndpointService '$name' must be a concrete class or object, not a $kind"

    public fun isAbstract(name: String): String =
        "@ServerEndpointService '$name' must not be abstract"

    public fun isSealed(name: String): String =
        "@ServerEndpointService '$name' must not be sealed"

    public fun nestedInResource(name: String, resource: String): String =
        "@ServerEndpointService '$name' must not be nested inside resource '$resource'"

    public fun noServiceImplemented(name: String): String =
        "@ServerEndpointService '$name' must implement at least one @Endpoint.Service interface, directly or transitively"

    public fun routeNameBlankOrInvalid(name: String, routeName: String): String =
        "@ServerEndpointService '$name': routeName '$routeName' must be non-blank and match [A-Za-z_][A-Za-z0-9_]*"

    public fun routeNameIsHardKeyword(name: String, routeName: String): String =
        "@ServerEndpointService '$name': routeName '$routeName' must not be a Kotlin hard keyword"

    public fun hideFromOpenApiOnNonEndpoint(name: String, function: String): String =
        "@ServerEndpointService.HideFromOpenApi on '$name.$function', which does not override an endpoint - " +
            "it goes on the override of the endpoint to leave out of the OpenAPI document"

    public fun hideFromOpenApiWithoutSpecs(name: String, function: String): String =
        "@ServerEndpointService.HideFromOpenApi on '$name.$function', but '$name' does not set withOpenApiSpecs - " +
            "there is no OpenAPI document to hide it from"
}
