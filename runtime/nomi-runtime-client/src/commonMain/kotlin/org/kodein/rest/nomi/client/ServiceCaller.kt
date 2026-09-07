package org.kodein.rest.nomi.client

import io.ktor.client.*
import io.ktor.client.request.*
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.client.ServiceCaller.CallBuilder
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.NType
import org.kodein.rest.nomi.on
import kotlin.reflect.KClass


public interface ServiceCaller<T : EndpointService> {
    public interface CallBuilder {
        public fun buildRequest(block: HttpRequestBuilder.() -> Unit)
    }
    public suspend fun <S, F : Error> call(
        successType: NType<S>,
        failureType: NType<F>,
        call: suspend context(CallBuilder) T.() -> Either<S, F>
    ): CallStatus.Result<S, F>
}

@InternalNomiAPI
public class NomiServiceCaller<T : EndpointService>(
    private val client: HttpClient,
    private val service: (HttpClient, HttpRequestBuilder.() -> Unit) -> T
) : ServiceCaller<T> {

    private class CallBuilderImpl : CallBuilder {
        val requestBuilders = ArrayList<HttpRequestBuilder.() -> Unit>()
        override fun buildRequest(block: HttpRequestBuilder.() -> Unit) {
            requestBuilders.add(block)
        }
        fun execute(builder: HttpRequestBuilder) {
            requestBuilders.forEach { it(builder) }
        }
    }

    override suspend fun <S, F : Error> call(
        successType: NType<S>,
        failureType: NType<F>,
        call: suspend context(CallBuilder) T.() -> Either<S, F>
    ): CallStatus.Result<S, F> {
        val callBuilder = CallBuilderImpl()

        try {
            return call(callBuilder, service(client) { callBuilder.execute(this) })
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
    noinline call: suspend context(CallBuilder) T.() -> Either<S, F>
): CallStatus.Result<S, F> =
    call(NType<S>(), NType<F>(), call)
