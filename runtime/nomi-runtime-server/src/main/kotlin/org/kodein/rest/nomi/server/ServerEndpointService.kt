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
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class ServerEndpointService(val routeName: String)
