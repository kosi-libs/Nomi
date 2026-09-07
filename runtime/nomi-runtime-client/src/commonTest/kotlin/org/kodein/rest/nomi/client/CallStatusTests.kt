package org.kodein.rest.nomi.client

import io.ktor.http.HttpStatusCode
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.NoFail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

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
}
