package org.kodein.rest.nomi.client

import io.ktor.client.*
import io.ktor.client.request.*
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.NType
import org.kodein.rest.nomi.on


public interface ServiceCaller<T : EndpointService> {
    public suspend fun <S, F : Error> call(
        successType: NType<S>,
        failureType: NType<F>,
        buildRequest: (HttpRequestBuilder.() -> Unit)? = null,
        call: suspend T.() -> Either<S, F>
    ): CallStatus.Result<S, F>
}

@InternalNomiAPI
public class NomiServiceCaller<T : EndpointService>(
    private val client: HttpClient,
    private val service: (HttpClient, HttpRequestBuilder.() -> Unit) -> T
) : ServiceCaller<T> {

    private val standardCallService = service(client, {})

    override suspend fun <S, F : Error> call(
        successType: NType<S>,
        failureType: NType<F>,
        buildRequest: (HttpRequestBuilder.() -> Unit)?,
        call: suspend T.() -> Either<S, F>
    ): CallStatus.Result<S, F> {

        try {
            val service = if (buildRequest != null) service(client, buildRequest) else standardCallService
            return service.call()
                .on(
                    valueType = successType,
                    errorType = failureType,
                    value = { CallStatus.Success(it) },
                    error = { CallStatus.CallFailure(it) }
                )
        } catch (e: EndpointStatusCodeException) {
            return CallStatus.ServerFailure(e.code, e.body)
        } catch (e: EndpointNetworkException) {
            return CallStatus.NetworkFailure(e.message ?: "", e.cause)
        }
    }

}

public suspend inline fun <T : EndpointService, reified S, reified F : Error> ServiceCaller<T>.call(
    crossinline call: suspend T.() -> Either<S, F>,
    crossinline buildRequest: HttpRequestBuilder.() -> Unit,
): CallStatus.Result<S, F> =
    call(
        successType = NType<S>(),
        failureType = NType<F>(),
        buildRequest = { buildRequest() },
        call = { call() }
    )

public suspend inline fun <T : EndpointService, reified S, reified F : Error> ServiceCaller<T>.call(
    crossinline call: suspend T.() -> Either<S, F>
): CallStatus.Result<S, F> =
    call(
        successType = NType<S>(),
        failureType = NType<F>(),
        buildRequest = null,
        call = { call() }
    )
