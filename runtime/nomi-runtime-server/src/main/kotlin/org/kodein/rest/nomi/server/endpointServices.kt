package org.kodein.rest.nomi.server

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.reflect.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.LightException
import org.kodein.rest.nomi.Stringer
import org.kodein.rest.nomi.onError
import org.kodein.rest.nomi.onValue
import org.kodein.rest.nomi.valueOr
import kotlin.coroutines.CoroutineContext


@PublishedApi
internal class EndpointStatusCodeException(val code: HttpStatusCode, message: String?) : LightException(message)

context(_: EndpointService)
public fun httpError(code: HttpStatusCode, message: String? = null): Nothing {
    require(code != HttpStatusCode.UnprocessableEntity) { "Cannot use UnprocessableEntity as an error code as it is reserved by Nomi." }
    throw EndpointStatusCodeException(code, message)
}

@PublishedApi
internal class RoutingCallElement(val call: RoutingCall) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key
    companion object Key : CoroutineContext.Key<RoutingCallElement>
}

context(_: EndpointService)
public suspend fun routingCall(): RoutingCall =
    currentCoroutineContext()[RoutingCallElement]?.call
        ?: error("No RoutingCall in coroutineContext")

@InternalNomiAPI
public suspend inline fun <T : EndpointService, reified S, reified F : Error> RoutingCall.respond(service: T, noinline block: suspend T.() -> Either<S, F>) {
    withContext(RoutingCallElement(this)) {
        try {
            service.block()
                .onValue { success ->
                    if (success is Unit || success == null) respond(HttpStatusCode.NoContent)
                    else this@respond.respond(status = HttpStatusCode.OK, message = success, typeInfo<S>())
                }
                .onError { failure ->
                    this@respond.respond(status = HttpStatusCode.UnprocessableEntity, message = failure, typeInfo<F>())
                }
        } catch (e: EndpointStatusCodeException) {
            if (e.message == null) respond(e.code)
            else respond(e.code, e.message!!)
        }
    }
}

@InternalNomiAPI
context(_: EndpointService)
public inline fun <reified T> decodeOrNull(stringer: Stringer<T>, encoded: String?): T? {
    if (encoded == null) return null
    return decode(stringer, encoded)
}

@InternalNomiAPI
context(_: EndpointService)
public inline fun <reified T> decode(stringer: Stringer<T>, encoded: String): T {
    return stringer.decode(encoded).valueOr { httpError(HttpStatusCode.BadRequest, it.reason) }
}
