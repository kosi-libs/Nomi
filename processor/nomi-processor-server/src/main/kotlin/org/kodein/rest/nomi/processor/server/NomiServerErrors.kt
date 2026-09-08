package org.kodein.rest.nomi.processor.server

/**
 * Every diagnostic message specific to `@ServerEndpointService` (SPECS.md § Server implementations).
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
}
