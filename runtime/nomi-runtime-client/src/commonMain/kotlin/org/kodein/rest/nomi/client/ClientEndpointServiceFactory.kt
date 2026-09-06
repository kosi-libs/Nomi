package org.kodein.rest.nomi.client

import org.kodein.rest.nomi.EndpointService
import kotlin.reflect.KClass


@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
@Repeatable
public annotation class ClientEndpointServiceFactory(val serviceClass: KClass<out EndpointService>, val name: String)
