package org.kodein.rest.nomi.client

import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.NoFail
import org.kodein.rest.nomi.client.callChain
import org.kodein.rest.nomi.isImpossible


/**
 * The state of a call: [Loading] while it is in flight, then one [Result] - a [Success] or one of
 * the three [Failure] kinds.
 *
 * [ServiceCaller.call] answers with a [Result], while [callFlow] and the other `Flow` builders here
 * emit the [Loading] state first, so the same type models a call in flight in a UI.
 *
 * The three sub-interfaces exist to let a signature name a subset of the states without listing
 * them: [Result] is "the call is over", [NotSuccess] is "there is no success value to read", and
 * [NotFailure] is "no failure was reported". [Loading] is both of the latter two, which is what
 * lets it pass through [mergeCall] and [fake] with its type arguments rewritten.
 *
 * Two kinds of throwable are deliberately *not* folded into a `CallStatus`: a
 * `CancellationException` raised mid-call propagates unchanged, so cancelling the caller's scope
 * still cancels the call, and so do the client-misconfiguration exceptions (a missing
 * `ContentNegotiation`, say) - answering those as a [NetworkFailure] would only hide what is
 * actually wrong.
 *
 * Like `Either`, this hierarchy stands in for the union types Kotlin does not have yet: it will be
 * replaced by a real union as soon as
 * [KT-68296](https://youtrack.jetbrains.com/issue/KT-68296/Union-Types-for-Errors) lands, so don't
 * get too attached.
 */
public sealed interface CallStatus<out S, out F : Error> {
    /** A call that is over: either a [Success] or a [Failure], never [Loading]. */
    public sealed interface Result<out S, out F : Error> : CallStatus<S, F>
    /** Every state but [Success]: there is no success value to read. */
    public sealed interface NotSuccess<out S, out F : Error> : CallStatus<S, F>
    /** Every state but a [Failure]: nothing went wrong, though the call may still be in flight. */
    public sealed interface NotFailure<out S, out F : Error> : CallStatus<S, F>

    /** The call is in flight. A single instance, whatever the type arguments - see [invoke]. */
    public data object Loading : NotSuccess<Any?, Error>, NotFailure<Any?, Error> {
        /**
         * This same instance, typed as a `CallStatus<S, F>`.
         *
         * `Loading` holds nothing, so one instance serves every call: the cast is why it is
         * declared over `Any?` and `Error` in the first place.
         */
        @Suppress("UNCHECKED_CAST")
        public operator fun <S, F : Error> invoke(): CallStatus<S, F> = this as CallStatus<S, F>
    }

    /** The endpoint answered `200`/`204`, with [success] as the decoded value. */
    public data class Success<out S, out F : Error>(val success: S) : Result<S, F>, NotFailure<S, F>

    /**
     * A call that is over and did not succeed: [NetworkFailure], [ServerFailure] or [CallFailure].
     */
    public sealed interface Failure<out S, out F : Error> : Result<S, F>, NotSuccess<S, F>

    /**
     * The request never reached the server. [cause] is the transport exception, when there is one.
     */
    public data class NetworkFailure<out S, out F : Error>(val message: String, val cause: Throwable? = null) : Failure<S, F>

    /**
     * The server answered a non-success status other than the `422` reserved for business failures
     * - whatever `httpError` raised, with [code] and the response [body] as it came.
     */
    public data class ServerFailure<out S, out F : Error>(val code: HttpStatusCode, val body: String) : Failure<S, F>

    /**
     * The endpoint answered `422` with the business [failure] it declared as its `Either`'s `E`.
     */
    public data class CallFailure<out S, out F : Error>(val failure: F) : Failure<S, F>

    /** Holds the [Success] factory for the calls that return nothing. */
    public companion object {
        private val unitSuccess = Success<Unit, NoFail>(Unit)

        /**
         * The [Success] of a call that returns nothing.
         *
         * `Unit` holds no information and the failure type is phantom, so one shared instance
         * serves every `Success<Unit, F>`.
         */
        @Suppress("UNCHECKED_CAST")
        public fun <F : Error> Success(): Success<Unit, F> = unitSuccess as Success<Unit, F>
    }
}

/**
 * Transforms a [CallStatus.CallFailure]'s business failure with [transform], passing every other
 * state through as-is - only the failure type changes.
 *
 * This is how a chain merges the failure types of the calls it links: each call's own `F` is mapped
 * into the one `F` the chain reports.
 */
public inline fun <S, F1 : Error, F2 : Error> CallStatus<S, F1>.mapFailure(transform: (F1) -> F2): CallStatus<S, F2> =
    when (this) {
        is CallStatus.CallFailure -> CallStatus.CallFailure(transform(failure))
        else -> @Suppress("UNCHECKED_CAST") (this as CallStatus<S, F2>)
    }

/** [mapFailure] over a [CallStatus.Result], which stays a `Result`. */
public inline fun <S, F1 : Error, F2 : Error> CallStatus.Result<S, F1>.mapFailure(transform: (F1) -> F2): CallStatus.Result<S, F2> =
    when (this) {
        is CallStatus.CallFailure -> CallStatus.CallFailure(transform(failure))
        else -> @Suppress("UNCHECKED_CAST") (this as CallStatus.Result<S, F2>)
    }

/**
 * Retypes the failure of a status that cannot carry one: a [NoFail] endpoint never answers a
 * [CallStatus.CallFailure], so its status fits any [F].
 *
 * A variance escape hatch, not a runtime check - the instance is returned untouched.
 */
public fun <S, F : Error> CallStatus<S, NoFail>.fakeFailure(): CallStatus<S, F> =
    mapFailure { it.isImpossible() }

/** [fakeFailure] over a [CallStatus.Result], which stays a `Result`. */
public fun <S, F : Error> CallStatus.Result<S, NoFail>.fakeFailure(): CallStatus.Result<S, F> =
    mapFailure { it.isImpossible() }

/**
 * Transforms a [CallStatus.Success]'s value with [transform], passing every other state through
 * as-is - only the success type changes.
 */
public inline fun <S1, S2, F : Error> CallStatus<S1, F>.mapSuccess(transform: (S1) -> S2): CallStatus<S2, F> =
    when (this) {
        is CallStatus.Success -> CallStatus.Success(transform(success))
        else -> @Suppress("UNCHECKED_CAST") (this as CallStatus<S2, F>)
    }

/** [mapSuccess] over a [CallStatus.Result], which stays a `Result`. */
public inline fun <S1, S2, F : Error> CallStatus.Result<S1, F>.mapSuccess(transform: (S1) -> S2): CallStatus.Result<S2, F> =
    when (this) {
        is CallStatus.Success -> CallStatus.Success(transform(success))
        else -> @Suppress("UNCHECKED_CAST") (this as CallStatus.Result<S2, F>)
    }

/**
 * Retypes the success of a status that holds none, to whatever [S] the call site needs.
 *
 * A variance escape hatch, not a runtime check - the instance is returned untouched. This is what
 * lets a [CallChainBuilder] report one call's failure as the failure of a chain returning
 * something else entirely.
 */
public fun <S, F : Error> CallStatus.NotSuccess<*, F>.fakeSuccess(): CallStatus.NotSuccess<S, F> =
    @Suppress("UNCHECKED_CAST") (this as CallStatus.NotSuccess<S, F>)

/** [fakeSuccess] over a [CallStatus.Failure], which stays a `Failure`. */
public fun <S, F : Error> CallStatus.Failure<*, F>.fakeSuccess(): CallStatus.Failure<S, F> =
    @Suppress("UNCHECKED_CAST") (this as CallStatus.Failure<S, F>)

/**
 * Retypes the failure of a status that holds none, to whatever [F] the call site needs.
 *
 * A variance escape hatch, not a runtime check - the instance is returned untouched. Unlike the
 * [NoFail] overload, this one is about a *state* that carries no failure, not an endpoint that
 * cannot fail.
 */
public fun <S, F : Error> CallStatus.NotFailure<S, *>.fakeFailure(): CallStatus.NotFailure<S, F> =
    @Suppress("UNCHECKED_CAST") (this as CallStatus.NotFailure<S, F>)

/** [fakeSuccess] and [fakeFailure] at once, for a [NoFail] status that holds no success either. */
public fun <S, F : Error> CallStatus.NotSuccess<*, NoFail>.fake(): CallStatus.NotSuccess<S, F> =
    fakeSuccess<S, NoFail>().fakeFailure<S, F>() as CallStatus.NotSuccess<S, F>

/** [fake] over a [CallStatus.Failure], which stays a `Failure`. */
public fun <S, F : Error> CallStatus.Failure<*, NoFail>.fake(): CallStatus.Failure<S, F> =
    fakeSuccess<S, NoFail>().fakeFailure<S, F>() as CallStatus.Failure<S, F>

/** The success value, or `null` for every other state - including a `null` success value. */
public fun <S> CallStatus<S, *>.successOrNull(): S? = (this as? CallStatus.Success)?.success

/**
 * The success value, or [onFailure] applied to the failure.
 *
 * [T] may be a supertype of [S], so the fallback is free to answer something other than a success
 * value.
 */
public inline fun <T, S : T, F : Error> CallStatus.Result<S, F>.successOr(onFailure: (CallStatus.Failure<S, F>) -> T): T =
    when (this) {
        is CallStatus.Success -> success
        is CallStatus.Failure -> onFailure(this)
    }

/** Runs [onSuccess] on the success value, if there is one, and returns this status unchanged. */
public inline fun <S, F : Error> CallStatus.Result<S, F>.onSuccess(onSuccess: (S) -> Unit): CallStatus.Result<S, F> {
    if (this is CallStatus.Success) onSuccess(success)
    return this
}

/** Runs [onFailure] on the failure, if there is one, and returns this status unchanged. */
public inline fun <S, F : Error> CallStatus.Result<S, F>.onFailure(onFailure: (CallStatus.Failure<S, F>) -> Unit): CallStatus.Result<S, F> {
    if (this is CallStatus.Failure) { onFailure(this) }
    return this
}

/**
 * Folds both branches down to one [R], applying [success] to the success value and [failure] to the
 * whole failure - which stays a [CallStatus.Failure], so the handler can tell the three kinds apart.
 */
public inline fun <S, F : Error, R> CallStatus.Result<S, F>.on(
    success: (S) -> R,
    failure: (CallStatus.Failure<S, F>) -> R,
): R =
    when (this) {
        is CallStatus.Success -> success(this@on.success)
        is CallStatus.Failure -> failure(this)
    }

/**
 * A cold `Flow` that emits [CallStatus.Loading], runs [call], then emits its result.
 *
 * Nothing runs until the flow is collected, and every collection calls again.
 * [ServiceCaller.flow] is this builder over a single endpoint call.
 */
public inline fun <S, F : Error> callFlow(
    crossinline call: suspend () -> CallStatus.Result<S, F>,
): Flow<CallStatus<S, F>> =
    flow {
        emit(CallStatus.Loading())
        call()
            .onSuccess { emit(CallStatus.Success(it)) }
            .onFailure { emit(it) }
    }

/** Runs [onSuccess] on every [CallStatus.Success] the flow emits, leaving the flow untouched. */
public inline fun <S, F : Error> Flow<CallStatus<S, F>>.onSuccess(
    crossinline onSuccess: suspend (S) -> Unit,
): Flow<CallStatus<S, F>> =
    onEach {
        if (it is CallStatus.Success) onSuccess(it.success)
    }

/** Runs [onFailure] on every [CallStatus.Failure] the flow emits, leaving the flow untouched. */
public inline fun <S, F : Error> Flow<CallStatus<S, F>>.onFailure(
    crossinline onFailure: suspend (CallStatus.Failure<S, F>) -> Unit,
): Flow<CallStatus<S, F>> =
    onEach {
        if (it is CallStatus.Failure) onFailure(it)
    }

/**
 * Continues with [call] on each success this flow emits, and passes every other state straight
 * through with its success type rewritten.
 *
 * The merged call is itself a [callFlow], so it emits its own [CallStatus.Loading] first: a
 * `callFlow { }.mergeCall { }` pipeline emits two `Loading` states, one per call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public inline fun <S1, S2, F : Error> Flow<CallStatus<S1, F>>.mergeCall(
    crossinline call: suspend (S1) -> CallStatus.Result<S2, F>,
): Flow<CallStatus<S2, F>> =
    flatMapMerge { status ->
        when (status) {
            is CallStatus.NotSuccess -> flowOf(@Suppress("UNCHECKED_CAST") (status as CallStatus.NotSuccess<S2, F>))
            is CallStatus.Success -> callFlow { call(status.success) }
        }
    }

/**
 * The receiver of a [callChain] block: [link] starts a call, and the chain ends on the
 * [CallStatus.Result] the block returns.
 *
 * Every link runs in this scope, so links started before the first `await` run concurrently. The
 * first one to fail ends the whole chain with its failure, cancelling the others - see [callChain].
 */
public class CallChainBuilder<F : Error>(
    private val scope: CoroutineScope,
    private val failure: suspend (CallStatus.Failure<*, F>) -> Unit
): CoroutineScope by scope {

    /**
     * Starts [call] and hands back its success value as a [Deferred].
     *
     * The returned `Deferred` only ever completes with a success: a failure ends the chain instead,
     * so an `await` on a failed link never resumes - the chain is cancelled around it. Map each
     * call's own failure type into the chain's [F] (with [mapFailure]) before linking it.
     */
    public fun <S> link(call: suspend () -> CallStatus.Result<S, F>): Deferred<S> {
        val def = CompletableDeferred<S>()
        launch {
            val result = call()
            when (result) {
                is CallStatus.Success -> { def.complete(result.success) }
                is CallStatus.Failure -> { failure(result) }
            }
        }
        return def
    }

    /** [link] over a nested [callChain]: a failure anywhere inside it fails the outer chain too. */
    public inline fun <S> linkChain(
        crossinline builder: suspend CallChainBuilder<F>.() -> CallStatus.Result<S, F>
    ): Deferred<S> =
        link {
            callChain {
                builder()
            }
        }

}

/**
 * Runs several calls as one: [builder] links them with [CallChainBuilder.link] and returns the
 * [CallStatus.Result] the chain answers with.
 *
 * The first link to fail is the chain's result, and its failure is reported with the chain's own
 * success type ([fakeSuccess]). That cancellation stays inside the chain: the caller's scope is
 * untouched, so a failed chain returns a failure rather than cancelling its caller.
 */
public suspend inline fun <S, F : Error> callChain(
    crossinline builder: suspend CallChainBuilder<F>.() -> CallStatus.Result<S, F>,
): CallStatus.Result<S, F> {
    lateinit var result: CallStatus.Result<S, F>
    coroutineScope {
        // Because coroutineScope returns a value, it propagates cancellation.
        // Using launch ensures that the scope does not propagate its cancellation if a call failure is returned.
        launch {
            val scope = this
            result = CallChainBuilder(
                scope = this,
                failure = {
                    result = it.fakeSuccess()
                    scope.cancel()
                },
            ).builder()
        }
    }
    return result
}

/** [callChain] as a [callFlow]: one [CallStatus.Loading], then the chain's result. */
public suspend inline fun <S, F : Error> callChainFlow(
    crossinline builder: suspend CallChainBuilder<F>.() -> CallStatus.Result<S, F>,
): Flow<CallStatus<S, F>> =
    callFlow {
        callChain {
            builder()
        }
    }

/** [mergeCall] with a whole [callChain] as its continuation, rather than a single call. */
@OptIn(ExperimentalCoroutinesApi::class)
public inline fun <S1, S2, F : Error> Flow<CallStatus<S1, F>>.mergeChain(
    crossinline builder: suspend CallChainBuilder<F>.(S1) -> CallStatus.Result<S2, F>,
): Flow<CallStatus<S2, F>> =
    flatMapMerge { status ->
        when (status) {
            is CallStatus.NotSuccess -> flowOf(@Suppress("UNCHECKED_CAST") (status as CallStatus.NotSuccess<S2, F>))
            is CallStatus.Success -> callChainFlow { builder(status.success) }
        }
    }
