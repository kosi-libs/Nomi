package org.kodein.rest.nomi.server


/**
 * Declares a class or object as the server-side implementation of every `@Endpoint.Service`
 * interface it implements: the server processor generates
 * `fun Route.<routeName>(service: <ThisClass>)` from it.
 *
 * Calling that function once, inside `routing { }`, registers every endpoint of every service the
 * class implements - each decoding its own path and query parameters and answering with the status
 * codes in README.adoc § Wire protocol. Across all those services together, no two endpoints may
 * resolve to the same `(path, method)` pair, since they all land on the one `Route` it builds.
 *
 * [routeName] must be non-blank, must match `[A-Za-z_][A-Za-z0-9_]*`, and must not be a Kotlin hard
 * keyword (SPECS.adoc § Server implementations).
 *
 * [withOpenApiSpecs] makes the generated routes describe themselves, so that ktor can assemble an
 * OpenAPI document from them - see [HideFromOpenApi] to leave one endpoint out.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class ServerEndpointService(
    val routeName: String,
    val withOpenApiSpecs: Boolean = false,
) {

    /**
     * Leaves this one endpoint out of the OpenAPI document its service otherwise produces.
     *
     * It goes on the *override* in the implementation class, not on the interface's declaration:
     * what a deployment exposes is the deployment's business, so two implementations of one service
     * may disagree about it, and the multiplatform service interface stays free of a server-only
     * concern. It does nothing unless [withOpenApiSpecs] is on, and the endpoint is still served
     * either way - only its description is withheld.
     */
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.FUNCTION)
    public annotation class HideFromOpenApi
}
