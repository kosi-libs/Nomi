package org.kodein.rest.nomi.client

import org.kodein.rest.nomi.EndpointService
import kotlin.reflect.KClass


/**
 * Declares, on an `object`, a factory for [serviceClass]: the client processor generates
 * `<Object>.<name>(client: HttpClient): ServiceCaller<<serviceClass>>` on it.
 *
 * Repeatable, so one object can front several services - one function per annotation, each with its
 * own [name]. [serviceClass] must be an `@Endpoint.Service` interface, since the type system alone
 * only guarantees it extends [EndpointService], and [name] must be a plain identifier that is not a
 * Kotlin hard keyword (SPECS.adoc § Client factories).
 *
 * Nomi neither creates nor configures the `HttpClient` handed to the generated function: the
 * application registers `ContentNegotiation` and a `defaultRequest` base URL on it (README.adoc §
 * Configuring the `HttpClient`).
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
@Repeatable
public annotation class ClientEndpointServiceFactory(val serviceClass: KClass<out EndpointService>, val name: String)
