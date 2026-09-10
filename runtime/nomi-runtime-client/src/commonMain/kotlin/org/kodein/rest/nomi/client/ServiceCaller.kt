package org.kodein.rest.nomi.client

import io.ktor.client.*
import io.ktor.client.request.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.NType
import org.kodein.rest.nomi.client.call
import org.kodein.rest.nomi.on
import org.kodein.rest.nomi.onValue


/**
 * The client's entry point to one service [T]: what a `@ClientEndpointServiceFactory`-generated
 * function returns.
 *
 * Call it through the reified [call] and [flow] extensions rather than through this member, which
 * exists for the generated implementation's sake.
 */
public interface ServiceCaller<T : EndpointService> {
    /**
     * Runs [call] on the service and folds its answer into a [CallStatus.Result].
     *
     * [successType] and [failureType] are what the reified extensions capture for it: they are how
     * the returned [Either] tells its two branches apart. [buildRequest], when given, customizes
     * this one call's request.
     */
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

/**
 * Runs one endpoint [call] with a per-call [buildRequest] customization - headers, authentication,
 * anything ktor's `HttpRequestBuilder` exposes.
 *
 * Kotlin's trailing-lambda syntax binds to the *last* parameter, so the endpoint call itself moves
 * inside the parentheses here: `caller.call({ Tools().Headers().get() }) { headers { ... } }`.
 */
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

/**
 * Runs one endpoint [call] and answers with its [CallStatus.Result].
 *
 * The block runs with the service as its receiver, so every resource factory and endpoint method is
 * in scope directly: `caller.call { Articles().get(page = 1) }`.
 */
public suspend inline fun <T : EndpointService, reified S, reified F : Error> ServiceCaller<T>.call(
    crossinline call: suspend T.() -> Either<S, F>
): CallStatus.Result<S, F> =
    call(
        successType = NType<S>(),
        failureType = NType<F>(),
        buildRequest = null,
        call = { call() }
    )

/** [flow] with the same per-call [buildRequest] customization the two-block [call] takes. */
public inline fun <T : EndpointService, reified S, reified F : Error> ServiceCaller<T>.flow(
    crossinline call: suspend T.() -> Either<S, F>,
    crossinline buildRequest: HttpRequestBuilder.() -> Unit,
): Flow<CallStatus<S, F>> =
    callFlow { call({ call() }) { buildRequest() } }

/**
 * [call] as a cold [callFlow]: collecting it emits [CallStatus.Loading], runs the call, then emits
 * its result.
 *
 * Nothing runs until the flow is collected, and every collection calls again.
 */
public inline fun <T : EndpointService, reified S, reified F : Error> ServiceCaller<T>.flow(
    crossinline call: suspend T.() -> Either<S, F>,
): Flow<CallStatus<S, F>> =
    callFlow { call { call() } }

