package org.kodein.rest.nomi.client

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.NoFail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

private data class TestFail(val reason: String) : Error
private data class TestFail2(val reason: String) : Error

class CallStatusTests {

    // --- mapFailure ----------------------------------------------------------------------------

    @Test
    fun `mapFailure transforms a CallFailure`() {
        val status: CallStatus<Int, TestFail> = CallStatus.CallFailure(TestFail("bad"))
        assertEquals(CallStatus.CallFailure(TestFail2("BAD")), status.mapFailure { TestFail2(it.reason.uppercase()) })

        val result: CallStatus.Result<Int, TestFail> = CallStatus.CallFailure(TestFail("bad"))
        assertEquals(CallStatus.CallFailure(TestFail2("BAD")), result.mapFailure { TestFail2(it.reason.uppercase()) })
    }

    @Test
    fun `mapFailure returns the same instance for every other variant`() {
        val success: CallStatus<Int, TestFail> = CallStatus.Success(5)
        assertSame<Any>(success, success.mapFailure { TestFail2(it.reason) })

        val loading: CallStatus<Int, TestFail> = CallStatus.Loading.invoke<Int, TestFail>()
        assertSame<Any>(loading, loading.mapFailure { TestFail2(it.reason) })

        val network: CallStatus<Int, TestFail> = CallStatus.NetworkFailure("oops")
        assertSame<Any>(network, network.mapFailure { TestFail2(it.reason) })

        val server: CallStatus<Int, TestFail> = CallStatus.ServerFailure(HttpStatusCode.InternalServerError, "boom")
        assertSame<Any>(server, server.mapFailure { TestFail2(it.reason) })

        val successResult: CallStatus.Result<Int, TestFail> = CallStatus.Success(5)
        assertSame<Any>(successResult, successResult.mapFailure { TestFail2(it.reason) })
    }

    // --- fakeFailure on CallStatus<S, NoFail> ---------------------------------------------------

    @Test
    fun `fakeFailure adapts the error type for statuses that can never actually carry NoFail`() {
        val success: CallStatus<Int, NoFail> = CallStatus.Success(5)
        val fakedSuccess: CallStatus<Int, TestFail> = success.fakeFailure()
        assertEquals(CallStatus.Success<Int, TestFail>(5), fakedSuccess)

        val network: CallStatus<Int, NoFail> = CallStatus.NetworkFailure("oops")
        val fakedNetwork: CallStatus<Int, TestFail> = network.fakeFailure()
        assertEquals(CallStatus.NetworkFailure<Int, TestFail>("oops"), fakedNetwork)

        val successResult: CallStatus.Result<Int, NoFail> = CallStatus.Success(5)
        val fakedResult: CallStatus.Result<Int, TestFail> = successResult.fakeFailure()
        assertEquals(CallStatus.Success<Int, TestFail>(5), fakedResult)
    }

    // --- mapSuccess ------------------------------------------------------------------------------

    @Test
    fun `mapSuccess transforms a Success`() {
        val status: CallStatus<Int, TestFail> = CallStatus.Success(5)
        assertEquals(CallStatus.Success<String, TestFail>("5"), status.mapSuccess { it.toString() })

        val result: CallStatus.Result<Int, TestFail> = CallStatus.Success(5)
        assertEquals(CallStatus.Success<String, TestFail>("5"), result.mapSuccess { it.toString() })
    }

    @Test
    fun `mapSuccess returns the same instance for every other variant`() {
        val loading: CallStatus<Int, TestFail> = CallStatus.Loading.invoke<Int, TestFail>()
        assertSame<Any>(loading, loading.mapSuccess { it.toString() })

        val callFailure: CallStatus<Int, TestFail> = CallStatus.CallFailure(TestFail("bad"))
        assertSame<Any>(callFailure, callFailure.mapSuccess { it.toString() })

        val network: CallStatus<Int, TestFail> = CallStatus.NetworkFailure("oops")
        assertSame<Any>(network, network.mapSuccess { it.toString() })

        val server: CallStatus<Int, TestFail> = CallStatus.ServerFailure(HttpStatusCode.InternalServerError, "boom")
        assertSame<Any>(server, server.mapSuccess { it.toString() })

        val callFailureResult: CallStatus.Result<Int, TestFail> = CallStatus.CallFailure(TestFail("bad"))
        assertSame<Any>(callFailureResult, callFailureResult.mapSuccess { it.toString() })
    }

    // --- successOrNull ---------------------------------------------------------------------------

    @Test
    fun `successOrNull returns the success value only for Success`() {
        assertEquals(5, CallStatus.Success<Int, TestFail>(5).successOrNull())
        assertNull(CallStatus.Loading.invoke<Int, TestFail>().successOrNull())
        assertNull(CallStatus.CallFailure<Int, TestFail>(TestFail("bad")).successOrNull())
        assertNull(CallStatus.NetworkFailure<Int, TestFail>("oops").successOrNull())
        assertNull(CallStatus.ServerFailure<Int, TestFail>(HttpStatusCode.InternalServerError, "boom").successOrNull())
    }

    // --- CallStatus.Success() / Loading() singleton factories -----------------------------------

    @Test
    fun `Success returns a shared Unit success regardless of the failure type`() {
        val a: CallStatus.Success<Unit, TestFail> = CallStatus.Success()
        val b: CallStatus.Success<Unit, TestFail2> = CallStatus.Success()
        assertEquals(Unit, a.success)
        assertSame<Any>(a, b)
    }

    @Test
    fun `Loading returns the shared Loading singleton regardless of type arguments`() {
        val a: CallStatus<Int, TestFail> = CallStatus.Loading.invoke<Int, TestFail>()
        val b: CallStatus<String, TestFail2> = CallStatus.Loading.invoke<String, TestFail2>()
        assertSame<Any>(CallStatus.Loading, a)
        assertSame<Any>(CallStatus.Loading, b)
    }

    // --- fakeSuccess / fakeFailure / fake identity casts -----------------------------------------

    @Test
    fun `fakeSuccess is an identity cast on NotSuccess`() {
        val callFailure: CallStatus.NotSuccess<Int, TestFail> = CallStatus.CallFailure(TestFail("bad"))
        assertSame<Any>(callFailure, callFailure.fakeSuccess<String, TestFail>())

        @Suppress("UNCHECKED_CAST")
        val loading = CallStatus.Loading as CallStatus.NotSuccess<Int, TestFail>
        assertSame<Any>(loading, loading.fakeSuccess<String, TestFail>())
    }

    @Test
    fun `fakeSuccess is an identity cast on Failure`() {
        val callFailure: CallStatus.Failure<Int, TestFail> = CallStatus.CallFailure(TestFail("bad"))
        assertSame<Any>(callFailure, callFailure.fakeSuccess<String, TestFail>())
    }

    @Test
    fun `fakeFailure is an identity cast on NotFailure`() {
        val success: CallStatus.NotFailure<Int, TestFail> = CallStatus.Success(5)
        assertSame<Any>(success, success.fakeFailure<Int, TestFail2>())

        @Suppress("UNCHECKED_CAST")
        val loading = CallStatus.Loading as CallStatus.NotFailure<Int, TestFail>
        assertSame<Any>(loading, loading.fakeFailure<Int, TestFail2>())
    }

    @Test
    fun `fake chains fakeSuccess and fakeFailure on NotSuccess`() {
        @Suppress("UNCHECKED_CAST")
        val loading = CallStatus.Loading as CallStatus.NotSuccess<Int, NoFail>
        val faked: CallStatus.NotSuccess<String, TestFail> = loading.fake()
        assertSame<Any>(loading, faked)
    }

    @Test
    fun `fake chains fakeSuccess and fakeFailure on Failure`() {
        val network: CallStatus.Failure<Int, NoFail> = CallStatus.NetworkFailure("oops")
        val faked: CallStatus.Failure<String, TestFail> = network.fake()
        assertSame<Any>(network, faked)
    }

    // --- successOr -------------------------------------------------------------------------------

    @Test
    fun `successOr returns the success value`() {
        val result: CallStatus.Result<Int, TestFail> = CallStatus.Success(5)
        assertEquals(5, result.successOr { fail("should not be called for a Success") })
    }

    @Test
    fun `successOr calls onFailure with the failure`() {
        val result: CallStatus.Result<Int, TestFail> = CallStatus.CallFailure(TestFail("bad"))
        assertEquals(-1, result.successOr { -1 })
        assertSame<Any>(result, result.successOr { it })
    }

    @Test
    fun `successOr can widen to a common supertype of the success and the fallback`() {
        val success: CallStatus.Result<Int, TestFail> = CallStatus.Success(5)
        val failure: CallStatus.Result<Int, TestFail> = CallStatus.NetworkFailure("oops")
        val fromSuccess: Any = success.successOr { "failed" }
        val fromFailure: Any = failure.successOr { "failed" }
        assertEquals(5, fromSuccess)
        assertEquals("failed", fromFailure)
    }

    // --- onSuccess / onFailure / on on a Result --------------------------------------------------

    @Test
    fun `onSuccess runs only on a Success and returns the same instance`() {
        val success: CallStatus.Result<Int, TestFail> = CallStatus.Success(5)
        val seen = mutableListOf<Int>()
        assertSame(success, success.onSuccess { seen += it })
        assertEquals(listOf(5), seen)

        val failure: CallStatus.Result<Int, TestFail> = CallStatus.CallFailure(TestFail("bad"))
        assertSame(failure, failure.onSuccess { seen += it })
        assertEquals(listOf(5), seen)
    }

    @Test
    fun `onFailure runs only on a Failure and returns the same instance`() {
        val failure: CallStatus.Result<Int, TestFail> = CallStatus.CallFailure(TestFail("bad"))
        val seen = mutableListOf<CallStatus.Failure<Int, TestFail>>()
        assertSame(failure, failure.onFailure { seen += it })
        assertEquals(listOf(failure), seen)

        val success: CallStatus.Result<Int, TestFail> = CallStatus.Success(5)
        assertSame(success, success.onFailure { seen += it })
        assertEquals(listOf(failure), seen)
    }

    @Test
    fun `on dispatches to the branch matching the result`() {
        val success: CallStatus.Result<Int, TestFail> = CallStatus.Success(5)
        assertEquals(
            "success 5",
            success.on(success = { "success $it" }, failure = { "failure $it" }),
        )

        val failure: CallStatus.Result<Int, TestFail> = CallStatus.CallFailure(TestFail("bad"))
        assertEquals(
            "failure $failure",
            failure.on(success = { "success $it" }, failure = { "failure $it" }),
        )
    }

    // --- callFlow --------------------------------------------------------------------------------

    @Test
    fun `callFlow emits Loading then the success`() = runTest {
        val statuses = callFlow<Int, TestFail> { CallStatus.Success(5) }.toList()
        assertEquals(
            listOf(CallStatus.Loading.invoke<Int, TestFail>(), CallStatus.Success(5)),
            statuses,
        )
    }

    @Test
    fun `callFlow emits Loading then the failure itself`() = runTest {
        val failure: CallStatus.Result<Int, TestFail> = CallStatus.CallFailure(TestFail("bad"))
        val statuses = callFlow { failure }.toList()
        assertEquals(listOf(CallStatus.Loading.invoke<Int, TestFail>(), failure), statuses)
        assertSame<Any>(failure, statuses[1])
    }

    @Test
    fun `callFlow only calls when it is collected`() = runTest {
        var calls = 0
        val flow = callFlow<Int, TestFail> {
            calls++
            CallStatus.Success(5)
        }
        assertEquals(0, calls)
        flow.toList()
        flow.toList()
        assertEquals(2, calls)
    }

    // --- Flow onSuccess / onFailure --------------------------------------------------------------

    @Test
    fun `Flow onSuccess is called for every Success and leaves the flow untouched`() = runTest {
        val statuses = listOf<CallStatus<Int, TestFail>>(
            CallStatus.Loading.invoke(),
            CallStatus.Success(1),
            CallStatus.CallFailure(TestFail("bad")),
            CallStatus.Success(2),
        )
        val seen = mutableListOf<Int>()
        assertEquals(statuses, statuses.asFlow().onSuccess { seen += it }.toList())
        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun `Flow onFailure is called for every Failure and leaves the flow untouched`() = runTest {
        val callFailure: CallStatus<Int, TestFail> = CallStatus.CallFailure(TestFail("bad"))
        val networkFailure: CallStatus<Int, TestFail> = CallStatus.NetworkFailure("oops")
        val statuses = listOf(
            CallStatus.Loading.invoke<Int, TestFail>(),
            callFailure,
            CallStatus.Success(1),
            networkFailure,
        )
        val seen = mutableListOf<CallStatus.Failure<Int, TestFail>>()
        assertEquals(statuses, statuses.asFlow().onFailure { seen += it }.toList())
        assertEquals(listOf(callFailure, networkFailure), seen)
    }

    // --- mergeCall -------------------------------------------------------------------------------

    @Test
    fun `mergeCall calls with the success value and prefixes the call with its own Loading`() = runTest {
        val statuses = flowOf<CallStatus<Int, TestFail>>(CallStatus.Success(2))
            .mergeCall { CallStatus.Success(it * 3) }
            .toList()
        assertEquals(
            listOf(CallStatus.Loading.invoke<Int, TestFail>(), CallStatus.Success(6)),
            statuses,
        )
    }

    @Test
    fun `mergeCall emits Loading then the failure of the merged call`() = runTest {
        val statuses = flowOf<CallStatus<Int, TestFail>>(CallStatus.Success(2))
            .mergeCall<Int, Int, TestFail> { CallStatus.CallFailure(TestFail("bad")) }
            .toList()
        assertEquals(
            listOf(CallStatus.Loading.invoke<Int, TestFail>(), CallStatus.CallFailure(TestFail("bad"))),
            statuses,
        )
    }

    @Test
    fun `mergeCall passes Loading through without calling`() = runTest {
        var called = false
        val statuses = flowOf(CallStatus.Loading.invoke<Int, TestFail>())
            .mergeCall<Int, String, TestFail> {
                called = true
                CallStatus.Success(it.toString())
            }
            .toList()
        assertEquals(listOf(CallStatus.Loading.invoke<String, TestFail>()), statuses)
        assertFalse(called)
    }

    @Test
    fun `mergeCall passes an upstream failure through without calling`() = runTest {
        var called = false
        val failure: CallStatus<Int, TestFail> = CallStatus.ServerFailure(HttpStatusCode.InternalServerError, "boom")
        val statuses = flowOf(failure)
            .mergeCall<Int, String, TestFail> {
                called = true
                CallStatus.Success(it.toString())
            }
            .toList()
        assertEquals(listOf(CallStatus.ServerFailure<String, TestFail>(HttpStatusCode.InternalServerError, "boom")), statuses)
        assertSame<Any>(failure, statuses.single())
        assertFalse(called)
    }

    // --- callChain / link ------------------------------------------------------------------------

    @Test
    fun `callChain returns the builder result when every link succeeds`() = runTest {
        val result = callChain<Int, TestFail> {
            val a = link { CallStatus.Success(2) }
            val b = link { CallStatus.Success(3) }
            CallStatus.Success(a.await() * b.await())
        }
        assertEquals(CallStatus.Success<Int, TestFail>(6), result)
    }

    @Test
    fun `callChain runs its links concurrently`() = runTest {
        // The first link only completes once the second one has run: this deadlocks unless both
        // links are actually running at the same time.
        val gate = CompletableDeferred<Int>()
        val result = callChain<Int, TestFail> {
            val a = link { CallStatus.Success(gate.await()) }
            val b = link {
                gate.complete(3)
                CallStatus.Success(2)
            }
            CallStatus.Success(a.await() * b.await())
        }
        assertEquals(CallStatus.Success<Int, TestFail>(6), result)
    }

    @Test
    fun `callChain returns the failure of a failed link`() = runTest {
        val result = callChain<Int, TestFail> {
            val failing = link<Int> { CallStatus.CallFailure(TestFail("bad")) }
            CallStatus.Success(failing.await())
        }
        assertEquals(CallStatus.CallFailure<Int, TestFail>(TestFail("bad")), result)
    }

    @Test
    fun `callChain returns a transport failure of a failed link as-is`() = runTest {
        val result = callChain<Int, TestFail> {
            val failing = link<Int> { CallStatus.NetworkFailure("oops") }
            CallStatus.Success(failing.await())
        }
        assertEquals(CallStatus.NetworkFailure<Int, TestFail>("oops"), result)
    }

    @Test
    fun `callChain cancels the pending links and the builder when a link fails`() = runTest {
        var pendingCancelled = false
        var builderResumed = false
        val pendingStarted = CompletableDeferred<Unit>()
        val result = callChain<Int, TestFail> {
            val pending = link<Int> {
                try {
                    pendingStarted.complete(Unit)
                    CompletableDeferred<Nothing>().await()
                } catch (cancellation: CancellationException) {
                    pendingCancelled = true
                    throw cancellation
                }
            }
            val failing = link<Int> {
                pendingStarted.await()
                CallStatus.CallFailure(TestFail("bad"))
            }
            val sum = failing.await() + pending.await()
            builderResumed = true
            CallStatus.Success(sum)
        }
        assertEquals(CallStatus.CallFailure<Int, TestFail>(TestFail("bad")), result)
        assertTrue(pendingCancelled)
        assertFalse(builderResumed)
    }

    @Test
    fun `callChain does not propagate the cancellation of a failed link to its caller`() = runTest {
        val result = callChain<Int, TestFail> {
            val failing = link<Int> { CallStatus.CallFailure(TestFail("bad")) }
            CallStatus.Success(failing.await())
        }
        assertEquals(CallStatus.CallFailure<Int, TestFail>(TestFail("bad")), result)
        // The chain's cancellation must stay inside the chain: this scope is still usable.
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(5, async { 5 }.await())
    }

    // --- linkChain -------------------------------------------------------------------------------

    @Test
    fun `linkChain nests a chain into a link`() = runTest {
        val result = callChain<Int, TestFail> {
            val nested = linkChain {
                val inner = link { CallStatus.Success(2) }
                CallStatus.Success(inner.await() * 3)
            }
            CallStatus.Success(nested.await() + 1)
        }
        assertEquals(CallStatus.Success<Int, TestFail>(7), result)
    }

    @Test
    fun `a failure inside a linkChain fails the outer chain`() = runTest {
        val result = callChain<Int, TestFail> {
            val nested = linkChain {
                val inner = link<Int> { CallStatus.CallFailure(TestFail("inner")) }
                CallStatus.Success(inner.await())
            }
            CallStatus.Success(nested.await() + 1)
        }
        assertEquals(CallStatus.CallFailure<Int, TestFail>(TestFail("inner")), result)
    }

    // --- callChainFlow ---------------------------------------------------------------------------

    @Test
    fun `callChainFlow emits Loading then the chain success`() = runTest {
        val statuses = callChainFlow<Int, TestFail> {
            val a = link { CallStatus.Success(2) }
            CallStatus.Success(a.await() * 3)
        }.toList()
        assertEquals(
            listOf(CallStatus.Loading.invoke<Int, TestFail>(), CallStatus.Success(6)),
            statuses,
        )
    }

    @Test
    fun `callChainFlow emits Loading then the chain failure`() = runTest {
        val statuses = callChainFlow<Int, TestFail> {
            val failing = link<Int> { CallStatus.CallFailure(TestFail("bad")) }
            CallStatus.Success(failing.await())
        }.toList()
        assertEquals(
            listOf(CallStatus.Loading.invoke<Int, TestFail>(), CallStatus.CallFailure(TestFail("bad"))),
            statuses,
        )
    }

    // --- mergeChain ------------------------------------------------------------------------------

    @Test
    fun `mergeChain builds a chain from the success value`() = runTest {
        val statuses = flowOf<CallStatus<Int, TestFail>>(CallStatus.Success(2))
            .mergeChain { value ->
                val doubled = link { CallStatus.Success(value * 2) }
                CallStatus.Success(doubled.await() + 1)
            }
            .toList()
        assertEquals(
            listOf(CallStatus.Loading.invoke<Int, TestFail>(), CallStatus.Success(5)),
            statuses,
        )
    }

    @Test
    fun `mergeChain emits Loading then the failure of the merged chain`() = runTest {
        val statuses = flowOf<CallStatus<Int, TestFail>>(CallStatus.Success(2))
            .mergeChain<Int, Int, TestFail> {
                val failing = link<Int> { CallStatus.CallFailure(TestFail("bad")) }
                CallStatus.Success(failing.await())
            }
            .toList()
        assertEquals(
            listOf(CallStatus.Loading.invoke<Int, TestFail>(), CallStatus.CallFailure(TestFail("bad"))),
            statuses,
        )
    }

    @Test
    fun `mergeChain passes NotSuccess statuses through without building a chain`() = runTest {
        var built = false
        val statuses = flowOf(CallStatus.Loading.invoke<Int, TestFail>())
            .mergeChain<Int, String, TestFail> { value ->
                built = true
                CallStatus.Success(value.toString())
            }
            .toList()
        assertEquals(listOf(CallStatus.Loading.invoke<String, TestFail>()), statuses)
        assertFalse(built)
    }
}
