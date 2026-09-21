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
import org.kodein.rest.nomi.client.ServiceCaller.KtorRequestBuilder
import org.kodein.rest.nomi.client.ServiceCaller.StreamingResponseHandler
import org.kodein.rest.nomi.on
import kotlin.coroutines.SuspendFunction1


/**
 * The client's entry point to one service [T]: what a `@ClientEndpointServiceFactory`-generated
 * function returns.
 *
 * Call it through the reified [call] and [flow] extensions rather than through this member, which
 * exists for the generated implementation's sake.
 */
public interface ServiceCaller<T : EndpointService> {
    
    /** A per-call customization of the outgoing request - headers, authentication, anything
     * ktor's `HttpRequestBuilder` exposes. `null` where a call adds none. */
    public typealias KtorRequestBuilder = suspend HttpRequestBuilder.() -> Unit

    /**
     * Consumes a response while it is still open, turning the endpoint's [S] into whatever [R] the
     * caller wants to keep.
     *
     * It receives the call as a *function* rather than as a value: invoking it performs the request
     * and reads the response, and the response stays readable for as long as this handler runs.
     * Whatever it returns becomes the call's result. See [callStreaming].
     */
    public typealias StreamingResponseHandler<S, R, F> = suspend (suspend () -> Either<S, F>) -> Either<R, F>
    
    /**
     * Runs [call] on the service and folds its answer into a [CallStatus.Result].
     *
     * [successType] and [failureType] are what the reified extensions capture for it: they are how
     * the returned [Either] tells its two branches apart. [buildRequest], when given, customizes
     * this one call's request.
     *
     * [call] invokes exactly one endpoint method: the service instance it runs on serves one request
     * and refuses a second.
     */
    public suspend fun <S, F : Error> call(
        successType: NType<S>,
        failureType: NType<F>,
        buildRequest: KtorRequestBuilder? = null,
        call: suspend T.() -> Either<S, F>
    ): CallStatus.Result<S, F>

    /**
     * Runs [call] with its response held open for [streamResponse], and folds what that handler
     * returns into a [CallStatus.Result].
     *
     * [resultType] and [failureType] are what the reified extension captures - note the result is
     * typed by the handler's [R], not by the endpoint's own success type. Use the reified
     * [callStreaming] extension rather than this member, which exists for the generated
     * implementation's sake.
     */
    public suspend fun <S, R, F : Error> callStreaming(
        resultType: NType<R>,
        failureType: NType<F>,
        call: suspend T.() -> Either<S, F>,
        buildRequest: KtorRequestBuilder? = null,
        streamResponse: StreamingResponseHandler<S, R, F>,
    ): CallStatus.Result<R, F>
}

/**
 * The [ServiceCaller] a `@ClientEndpointServiceFactory`-generated function returns.
 *
 * [service] is the generated implementation's constructor: it takes the client, the per-call
 * request customization, and the streaming handler - the last two `null` for a plain [call], which
 * is why one service instance can be built once and reused for every uncustomized call.
 */
@InternalNomiAPI
public class NomiServiceCaller<T : EndpointService>(
    private val client: HttpClient,
    private val service: (HttpClient, KtorRequestBuilder?, StreamingResponseHandler<*, *, *>?) -> T
) : ServiceCaller<T> {

    override suspend fun <S, F : Error> call(
        successType: NType<S>,
        failureType: NType<F>,
        buildRequest: KtorRequestBuilder?,
        call: suspend T.() -> Either<S, F>
    ): CallStatus.Result<S, F> {

        try {
            // A fresh instance per call: the customization has to be carried by the instance itself,
            // since that is where the generated endpoint methods read it from, and an instance is
            // single-use anyway (NomiClientEndpointService tracks whether it has been called).
            val service = service(client, buildRequest, null)
            // Either is a value class over one `content: Any?` - it carries no runtime type of its
            // own, so which branch it holds is decided by the NTypes the caller captured, not by
            // the static type parameters here.
            return service.call()
                .on(
                    valueType = successType,
                    errorType = failureType,
                    value = { CallStatus.Success(it) },
                    error = { CallStatus.CallFailure(it) }
                )
        // Only these two escape an endpoint call; everything else is a genuine bug and propagates.
        } catch (e: EndpointStatusCodeException) {
            return CallStatus.ServerFailure(e.code, e.body)
        } catch (e: EndpointNetworkException) {
            return CallStatus.NetworkFailure(e.message ?: "", e.cause)
        }
    }

    override suspend fun <S, R, F : Error> callStreaming(
        resultType: NType<R>,
        failureType: NType<F>,
        call: suspend T.() -> Either<S, F>,
        buildRequest: KtorRequestBuilder?,
        streamResponse: StreamingResponseHandler<S, R, F>,
    ): CallStatus.Result<R, F> {
        try {
            // Cast 1, widening. NomiClientEndpointService is not generic, so it can only hold the
            // handler star-projected. A function type is contravariant in its parameter, so
            // <S, R, F> is not a subtype of <*, *, *> and the compiler cannot see this as safe -
            // but nothing ever reads the field back except HttpClient.call, which narrows it again
            // to the very S and F this call was built with (cast 3 there).
            val service = service(client, buildRequest, @Suppress("UNCHECKED_CAST") (streamResponse as StreamingResponseHandler<*, *, *>))
            // Cast 2, the other end of the laundering. `call` is declared to answer Either<S, F> -
            // the endpoint's own success type - but with a handler installed, what HttpClient.call
            // actually returns is whatever the handler returned, an Either<R, F>. That is a
            // relabelling rather than a conversion: Either is a value class wrapping one Any?, so
            // the two types share a runtime representation and nothing is reinterpreted.
            //
            // What makes it correct rather than merely legal is the next line: the value is
            // discriminated with `resultType`, R's NType, so the runtime check matches what the
            // handler actually put in there.
            return (@Suppress("UNCHECKED_CAST") (service.call() as Either<R, F>))
                .on(
                    valueType = resultType,
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
    noinline call: suspend T.() -> Either<S, F>,
    noinline buildRequest: KtorRequestBuilder,
): CallStatus.Result<S, F> =
    call(
        successType = NType<S>(),
        failureType = NType<F>(),
        buildRequest = buildRequest,
        call = call
    )

/**
 * Runs one endpoint [call] and answers with its [CallStatus.Result].
 *
 * The block runs with the service as its receiver, so every resource factory and endpoint method is
 * in scope directly: `caller.call { Articles().get(page = 1) }`.
 *
 * It calls exactly one endpoint method. Building resources is free - only the endpoint call itself
 * counts - but a block invoking two of them fails: call twice instead.
 */
public suspend inline fun <T : EndpointService, reified S, reified F : Error> ServiceCaller<T>.call(
    noinline call: suspend T.() -> Either<S, F>
): CallStatus.Result<S, F> =
    call(
        successType = NType<S>(),
        failureType = NType<F>(),
        buildRequest = null,
        call = call
    )

/**
 * Runs one endpoint [call] and consumes its response *while it is still open*.
 *
 * A plain [call] reads the whole response before returning, so an answer you mean to consume as a
 * stream has nowhere to be read. [streamResponse] is that scope: it is handed a function that
 * performs the call, and may read the response for as long as it runs. Whatever it returns is the
 * call's result, so it can narrow or transform the answer as it consumes it:
 *
 * ```kotlin
 * articles.callStreaming({ Article(id).get() }) { get ->
 *     get().mapValue { it.content }
 * }
 * ```
 *
 * Kotlin's trailing-lambda syntax binds to the *last* parameter, so the endpoint call moves inside
 * the parentheses - the trailing lambda here is the handler, and [buildRequest] sits between them
 * when a call needs one. A failure folds exactly as it does for [call]: the handler still runs and
 * sees the failing `Either`, while a transport or status-code failure never reaches it and becomes
 * a [CallStatus.NetworkFailure] or [CallStatus.ServerFailure].
 */
public suspend inline fun <T : EndpointService, S, reified R, reified F : Error> ServiceCaller<T>.callStreaming(
    noinline call: suspend T.() -> Either<S, F>,
    noinline buildRequest: KtorRequestBuilder? = null,
    noinline streamResponse: StreamingResponseHandler<S, R, F>,
): CallStatus.Result<R, F> =
    callStreaming(
        resultType = NType<R>(),
        failureType = NType<F>(),
        buildRequest = buildRequest,
        streamResponse = streamResponse,
        call = call,
    )


/** [flow] with the same per-call [buildRequest] customization the two-block [call] takes. */
public inline fun <T : EndpointService, reified S, reified F : Error> ServiceCaller<T>.flow(
    noinline call: suspend T.() -> Either<S, F>,
    noinline buildRequest: KtorRequestBuilder,
): Flow<CallStatus<S, F>> =
    callFlow { call(call, buildRequest) }

/**
 * [call] as a cold [callFlow]: collecting it emits [CallStatus.Loading], runs the call, then emits
 * its result.
 *
 * Nothing runs until the flow is collected, and every collection calls again.
 */
public inline fun <T : EndpointService, reified S, reified F : Error> ServiceCaller<T>.flow(
    noinline call: suspend T.() -> Either<S, F>,
): Flow<CallStatus<S, F>> =
    callFlow { call(call) }

