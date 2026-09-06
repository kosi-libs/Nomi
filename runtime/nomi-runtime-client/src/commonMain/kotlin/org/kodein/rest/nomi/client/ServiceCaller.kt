package org.kodein.rest.nomi.client

import io.ktor.client.*
import io.ktor.client.request.*
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.client.ServiceCaller.CallBuilder
import org.kodein.rpc.nomi.Either
import org.kodein.rpc.nomi.Error
import org.kodein.rpc.nomi.on
import kotlin.reflect.KClass


public interface ServiceCaller<T : EndpointService> {
    public interface CallBuilder {
        public fun buildRequest(block: HttpRequestBuilder.() -> Unit)
    }
    public suspend fun <S, F : Error> execute(
        successClass: KClass<S & Any>,
        failureClass: KClass<F>,
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

    override suspend fun <S, F : Error> execute(
        successClass: KClass<S & Any>,
        failureClass: KClass<F>,
        call: suspend context(CallBuilder) T.() -> Either<S, F>
    ): CallStatus.Result<S, F> {
        val callBuilder = CallBuilderImpl()

        return call(callBuilder, service(client) { callBuilder.execute(this) })
            .on(
                valueClass = successClass,
                errorClass = failureClass,
                value = { CallStatus.Success(it) },
                error = { CallStatus.CallFailure(it) }
            )
    }

}

public suspend inline fun <T : EndpointService, reified S : Any, reified F : Error> ServiceCaller<T>.execute(
    noinline call: suspend context(CallBuilder) T.() -> Either<S, F>
): CallStatus.Result<S, F> =
    execute(S::class, F::class, call)
