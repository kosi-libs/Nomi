package org.kodein.rest.nomi.client

import io.ktor.http.*
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.NoFail
import org.kodein.rest.nomi.isImpossible


// Will be advantageously replaced by Union for Errors: https://youtrack.jetbrains.com/issue/KT-68296/Union-Types-for-Errors
// As soon as Union is available, this will be replaced by Union, so don't get too attached!
public sealed interface CallStatus<out S, out F : Error> {
    public sealed interface Result<out S, out F : Error> : CallStatus<S, F>
    public sealed interface NotSuccess<out S, out F : Error> : CallStatus<S, F>
    public sealed interface NotFailure<out S, out F : Error> : CallStatus<S, F>
    public data object Loading : NotSuccess<Any?, Error>, NotFailure<Any?, Error> {
        @Suppress("UNCHECKED_CAST")
        public operator fun <S, F : Error> invoke(): CallStatus<S, F> = this as CallStatus<S, F>
    }

    public data class Success<out S, out F : Error>(val success: S) : Result<S, F>, NotFailure<S, F>
    public sealed interface Failure<out S, out F : Error> : Result<S, F>, NotSuccess<S, F>

    public data class NetworkFailure<out S, out F : Error>(val message: String, val cause: Throwable? = null) : Failure<S, F>

    public data class ServerFailure<out S, out F : Error>(val code: HttpStatusCode, val body: String) : Failure<S, F>
    public data class CallFailure<out S, out F : Error>(val failure: F) : Failure<S, F>

    public companion object {
        private val unitSuccess = Success<Unit, NoFail>(Unit)

        @Suppress("UNCHECKED_CAST")
        public fun <F : Error> Success(): Success<Unit, F> = unitSuccess as Success<Unit, F>
    }
}

public inline fun <S, F1 : Error, F2 : Error> CallStatus<S, F1>.mapFailure(transform: (F1) -> F2): CallStatus<S, F2> =
    when (this) {
        is CallStatus.CallFailure -> CallStatus.CallFailure(transform(failure))
        else -> @Suppress("UNCHECKED_CAST") (this as CallStatus<S, F2>)
    }

public inline fun <S, F1 : Error, F2 : Error> CallStatus.Result<S, F1>.mapFailure(transform: (F1) -> F2): CallStatus.Result<S, F2> =
    when (this) {
        is CallStatus.CallFailure -> CallStatus.CallFailure(transform(failure))
        else -> @Suppress("UNCHECKED_CAST") (this as CallStatus.Result<S, F2>)
    }

public fun <S, F : Error> CallStatus<S, NoFail>.fakeFailure(): CallStatus<S, F> =
    mapFailure { it.isImpossible() }

public fun <S, F : Error> CallStatus.Result<S, NoFail>.fakeFailure(): CallStatus.Result<S, F> =
    mapFailure { it.isImpossible() }

public inline fun <S1, S2, F : Error> CallStatus<S1, F>.mapSuccess(transform: (S1) -> S2): CallStatus<S2, F> =
    when (this) {
        is CallStatus.Success -> CallStatus.Success(transform(success))
        else -> @Suppress("UNCHECKED_CAST") (this as CallStatus<S2, F>)
    }

public inline fun <S1, S2, F : Error> CallStatus.Result<S1, F>.mapSuccess(transform: (S1) -> S2): CallStatus.Result<S2, F> =
    when (this) {
        is CallStatus.Success -> CallStatus.Success(transform(success))
        else -> @Suppress("UNCHECKED_CAST") (this as CallStatus.Result<S2, F>)
    }

public fun <S, F : Error> CallStatus.NotSuccess<*, F>.fakeSuccess(): CallStatus.NotSuccess<S, F> =
    @Suppress("UNCHECKED_CAST") (this as CallStatus.NotSuccess<S, F>)

public fun <S, F : Error> CallStatus.Failure<*, F>.fakeSuccess(): CallStatus.Failure<S, F> =
    @Suppress("UNCHECKED_CAST") (this as CallStatus.Failure<S, F>)

public fun <S, F : Error> CallStatus.NotFailure<S, *>.fakeFailure(): CallStatus.NotFailure<S, F> =
    @Suppress("UNCHECKED_CAST") (this as CallStatus.NotFailure<S, F>)

public fun <S, F : Error> CallStatus.NotSuccess<*, NoFail>.fake(): CallStatus.NotSuccess<S, F> =
    fakeSuccess<S, NoFail>().fakeFailure<S, F>() as CallStatus.NotSuccess<S, F>

public fun <S, F : Error> CallStatus.Failure<*, NoFail>.fake(): CallStatus.Failure<S, F> =
    fakeSuccess<S, NoFail>().fakeFailure<S, F>() as CallStatus.Failure<S, F>

public fun <S> CallStatus<S, *>.successOrNull(): S? = (this as? CallStatus.Success)?.success

//public inline fun <T, S : T, F : Error> CallStatus.Result<S, F>.successOr(onFailure: (CallStatus.Failure<S, F>) -> T): T =
//    when (this) {
//        is CallStatus.Success -> success
//        is CallStatus.Failure -> onFailure(this)
//    }
//
//public inline fun <S, F : Error> CallStatus.Result<S, F>.onSuccess(onSuccess: (S) -> Unit): CallStatus.Result<S, F> {
//    if (this is CallStatus.Success) onSuccess(success)
//    return this
//}
//
//public inline fun <S, F : Error> CallStatus.Result<S, F>.onFailure(onFailure: (CallStatus.Failure<S, F>) -> Unit): CallStatus.Result<S, F> {
//    if (this is CallStatus.Failure) { onFailure(this) }
//    return this
//}
//
//public inline fun <S, F : Error, R> CallStatus.Result<S, F>.on(
//    success: (S) -> R,
//    failure: (CallStatus.Failure<S, F>) -> R,
//): R =
//    when (this) {
//        is CallStatus.Success -> success(this@on.success)
//        is CallStatus.Failure -> failure(this)
//    }
//
//public inline fun <S, F : Error> callFlow(
//    crossinline call: suspend () -> CallStatus.Result<S, F>,
//): Flow<CallStatus<S, F>> =
//    flow {
//        emit(CallStatus.Loading())
//        call()
//            .onSuccess { emit(CallStatus.Success(it)) }
//            .onFailure { emit(it) }
//    }
//
//public inline fun <S, F : Error> Flow<CallStatus<S, F>>.onSuccess(
//    crossinline onSuccess: suspend (S) -> Unit,
//): Flow<CallStatus<S, F>> =
//    onEach {
//        if (it is CallStatus.Success) onSuccess(it.success)
//    }
//
//public inline fun <S, F : Error> Flow<CallStatus<S, F>>.onFailure(
//    crossinline onFailure: suspend (CallStatus.Failure<S, F>) -> Unit,
//): Flow<CallStatus<S, F>> =
//    onEach {
//        if (it is CallStatus.Failure) onFailure(it)
//    }
//
//@OptIn(ExperimentalCoroutinesApi::class)
//public inline fun <S1, S2, F : Error> Flow<CallStatus<S1, F>>.chainCall(
//    crossinline call: suspend (S1) -> CallStatus.Result<S2, F>,
//): Flow<CallStatus<S2, F>> =
//    flatMapMerge { status ->
//        when (status) {
//            is CallStatus.NotSuccess -> flowOf(@Suppress("UNCHECKED_CAST") (status as CallStatus.NotSuccess<S2, F>))
//            is CallStatus.Success -> callFlow { call(status.success) }
//        }
//    }
//
//public class MergeCallsBuilder<F : Error>(
//    private val scope: CoroutineScope,
//    private val failure: suspend (CallStatus.Failure<*, F>) -> Unit
//): CoroutineScope by scope {
//
//    public fun <S> call(call: suspend () -> CallStatus.Result<S, F>): Deferred<S> {
//        val def = CompletableDeferred<S>()
//        launch {
//            val result = call()
//            when (result) {
//                is CallStatus.Success -> { def.complete(result.success) }
//                is CallStatus.Failure -> { failure(result) }
//            }
//        }
//        return def
//    }
//
//    public inline fun <S> calls(
//        crossinline builder: suspend MergeCallsBuilder<F>.() -> S
//    ): Deferred<S> =
//        call {
//            mergeCalls {
//                builder()
//            }
//        }
//
//}
//
//public suspend inline fun <S, F : Error> mergeCalls(
//    crossinline builder: suspend MergeCallsBuilder<F>.() -> S,
//): CallStatus.Result<S, F> {
//    lateinit var result: CallStatus.Result<S, F>
//    coroutineScope {
//        // Because coroutineScope returns a value, it propagates cancellation.
//        // Using launch ensures that the scope does not propagate its cancellation if a call failure is returned.
//        launch {
//            val scope = this
//            val success = MergeCallsBuilder(
//                scope = this,
//                failure = {
//                    result = it.fakeSuccess()
//                    scope.cancel()
//                },
//            ).builder()
//            result = CallStatus.Success(success)
//        }
//    }
//    return result
//}
//
//public suspend inline fun <S, F : Error> callsFlow(
//    crossinline builder: suspend MergeCallsBuilder<F>.() -> S,
//): Flow<CallStatus<S, F>> =
//    callFlow {
//        mergeCalls {
//            builder()
//        }
//    }
//
//@OptIn(ExperimentalCoroutinesApi::class)
//public inline fun <S1, S2, F : Error> Flow<CallStatus<S1, F>>.chainCalls(
//    crossinline builder: suspend MergeCallsBuilder<F>.(S1) -> S2,
//): Flow<CallStatus<S2, F>> =
//    flatMapMerge { status ->
//        when (status) {
//            is CallStatus.NotSuccess -> flowOf(@Suppress("UNCHECKED_CAST") (status as CallStatus.NotSuccess<S2, F>))
//            is CallStatus.Success -> callsFlow { builder(status.success) }
//        }
//    }
//
