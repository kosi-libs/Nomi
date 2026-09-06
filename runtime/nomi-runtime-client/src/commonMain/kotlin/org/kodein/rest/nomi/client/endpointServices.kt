package org.kodein.rest.nomi.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.LightException
import org.kodein.rest.nomi.Stringer
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.Either.Companion.error
import org.kodein.rest.nomi.Either.Companion.value
import org.kodein.rest.nomi.Error
import kotlin.coroutines.cancellation.CancellationException


@InternalNomiAPI
public sealed class EndpointException(message: String?, cause: Throwable? = null) : LightException(message, cause)

@InternalNomiAPI
public class EndpointStatusCodeException(public val code: HttpStatusCode, public val body: String) : EndpointException(code.description)

@InternalNomiAPI
public class EndpointNetworkException(message: String?, override val cause: Throwable) : EndpointException(message, cause)

@InternalNomiAPI
public interface NomiClientEndpointService : EndpointService {
    public val buildRequest: HttpRequestBuilder.() -> Unit
}

@InternalNomiAPI
context(service: NomiClientEndpointService)
public suspend inline fun <reified S, reified F : Error> HttpClient.call(
    pathSegments: List<String>,
    method: HttpMethod,
    buildRequest: HttpRequestBuilder.() -> Unit = {}
): Either<S, F> {
    val statement = prepareRequest {
        url.pathSegments += pathSegments
        this.method = method
        buildRequest(this)
        service.buildRequest(this)
    }
    val response = try {
        statement.execute()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw EndpointNetworkException(e.message, e)
    }
    if (response.status.isSuccess()) return value(response.body())
    if (response.status == HttpStatusCode.UnprocessableEntity) return error(response.body())
    throw EndpointStatusCodeException(response.status, response.bodyAsText())
}

@InternalNomiAPI
context(_: NomiClientEndpointService)
public fun <T> HttpRequestBuilder.appendParameter(
    name: String,
    value: T? = null,
    urlizer: Stringer<T>
) {
    if (value != null) url.parameters.append(name, urlizer.encode(value))
}
