package org.kodein.rest.nomi.server


@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class ServerEndpointService(val routeName: String)
