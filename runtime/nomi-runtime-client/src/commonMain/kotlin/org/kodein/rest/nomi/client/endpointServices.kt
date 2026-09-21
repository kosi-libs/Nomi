package org.kodein.rest.nomi.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.reflect.*
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.Either.Companion.error
import org.kodein.rest.nomi.Either.Companion.unit
import org.kodein.rest.nomi.Either.Companion.value
import io.ktor.http.content.ChannelWriterContent
import io.ktor.utils.io.writeSource
import org.kodein.rest.nomi.EndpointBinary
import org.kodein.rest.nomi.EndpointFile
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.LightException
import org.kodein.rest.nomi.Stringer
import kotlin.coroutines.cancellation.CancellationException


@InternalNomiAPI
public sealed class EndpointException(message: String?, cause: Throwable? = null) : LightException(message, cause)

@InternalNomiAPI
public class EndpointStatusCodeException(public val code: HttpStatusCode, public val body: String) : EndpointException(code.description)

@InternalNomiAPI
public class EndpointNetworkException(message: String?, override val cause: Throwable) : EndpointException(message, cause)

/**
 * What a generated client implementation extends, beyond the service interface it implements: the
 * two things its [ServiceCaller] hands its constructor, plus every helper the generated code calls.
 *
 * Those helpers are members rather than top-level functions so that generated code can call them
 * unqualified, with no import and no receiver to thread through - being inherited, they are simply in
 * scope inside the implementation's own endpoint methods.
 *
 * One instance serves one endpoint call: [call] refuses a second one.
 */
@InternalNomiAPI
public abstract class NomiClientEndpointService(
    /** Applied to the request this instance makes, after the endpoint's own building. `null` when
     * the caller added no per-call customization. */
    public val buildRequest: ServiceCaller.KtorRequestBuilder?,

    /** Set when the caller asked for the response to stay open - see
     * [ServiceCaller.StreamingResponseHandler]. `null` makes [call] read the response eagerly. */
    public val streamingHandler: ServiceCaller.StreamingResponseHandler<*, *, *>?
) : EndpointService {

    private var called: Boolean = false

    /** Reads a response into the endpoint's `Either`, per README.adoc § Wire protocol: `204` is the
     * empty success, `422` the business failure, any other non-success an [EndpointStatusCodeException]. */
    @InternalNomiAPI
    private suspend fun <S, F : Error> HttpResponse.eitherBody(
        successType: TypeInfo,
        errorType: TypeInfo,
    ): Either<S, F> =
        if (status.isSuccess()) {
            if (status == HttpStatusCode.NoContent) {
                @Suppress("UNCHECKED_CAST")
                if (successType.type == Unit::class) unit<F>() as Either<S, F>
                else value(null as S)
            } else value(body(successType))
        } else {
            if (status == HttpStatusCode.UnprocessableEntity) error(body(errorType))
            else throw EndpointStatusCodeException(status, bodyAsText())
        }

    /** Wraps the transport in [EndpointNetworkException], leaving cancellation and client
     * misconfiguration (an `IllegalStateException`, typically a missing `ContentNegotiation`) to
     * propagate untouched. */
    @InternalNomiAPI
    private inline fun <R> callTry(block: () -> R): R =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: EndpointException) {
            // Nomi's own control flow, not a transport failure. It matters in the streaming branch,
            // where the response is read *inside* this block: without this, an unexpected status would
            // arrive as a NetworkFailure there and as a ServerFailure on the non-streaming path.
            throw e
        } catch (e: Throwable) {
            throw EndpointNetworkException(e.message, e)
        }

    @InternalNomiAPI
    @PublishedApi
    internal suspend fun <S, F : Error> HttpClient.call(
        successType: TypeInfo,
        errorType: TypeInfo,
        pathSegments: List<String>,
        method: HttpMethod,
        buildRequest: ServiceCaller.KtorRequestBuilder = {}
    ): Either<S, F> {
        check(!called) { "A ServiceCaller call can only call one service method." }
        called = true

        // Nothing is sent yet: prepareRequest describes the request, and one of the two branches below
        // is what actually executes it. The endpoint's own building runs first and the caller's
        // per-call customization second, so a caller can override what the endpoint set.
        val statement = prepareRequest {
            url.pathSegments += pathSegments
            this.method = method
            buildRequest(this)
            this@NomiClientEndpointService.buildRequest?.invoke(this)
        }

        // The whole streaming feature is this fork: whether a caller asked for the response to stay
        // open, and so whether the body is read within a scope or saved up front.
        if (streamingHandler != null) {
            // Cast 3, narrowing back what NomiServiceCaller.callStreaming had to widen to <*, *, *>.
            // S and F are recovered from this function's own type parameters, which is sound because
            // the handler was installed for exactly one `call { }` lambda, and that lambda's declared
            // Either<S, F> is this endpoint's. A lambda invoking two endpoints would hand the same
            // handler two different S/F pairs, which is exactly what the `called` guard above rules
            // out - that check is what makes this narrowing sound, not merely conventional.
            //
            // R is pinned to Any because this function has no R of its own: it never inspects what the
            // handler returns, it only passes it along. Any is enough to invoke the handler.
            @Suppress("UNCHECKED_CAST")
            streamingHandler as ServiceCaller.StreamingResponseHandler<S, Any, F>
            val result = callTry {
                // The handler runs *inside* execute's block, so the response is still open while it
                // works. It is handed a function rather than a value: nothing is read until it calls,
                // and eitherBody - the read - therefore happens within this scope too.
                statement.execute { response ->
                    streamingHandler { response.eitherBody(successType, errorType) }
                }
            }
            // Cast 4, the same relabelling as cast 2 and for the same reason: `result` is the
            // handler's Either<R, F>, but this function's signature - and the generated endpoint method
            // calling it - can only speak of Either<S, F>. Either is a value class over one Any?, so
            // both are the same object; callStreaming casts it back and discriminates it with R's
            // NType. Nothing else can land here: callStreaming is the only thing that sets a handler.
            @Suppress("UNCHECKED_CAST")
            return result as Either<S, F>
        } else {
            // No handler: execute() saves the whole body before returning, so the response is closed
            // by the time eitherBody reads it - from memory. Note eitherBody sits *outside* callTry
            // here, while the streaming branch has it inside; that is why callTry must let
            // EndpointException through, or the two branches would disagree about an unexpected status.
            val response = callTry { statement.execute() }
            return response.eitherBody(successType, errorType)
        }
    }

    @InternalNomiAPI
    public suspend inline fun <reified S, reified F : Error> HttpClient.call(
        pathSegments: List<String>,
        method: HttpMethod,
        noinline buildRequest: ServiceCaller.KtorRequestBuilder = {}
    ): Either<S, F> = call(
        pathSegments = pathSegments,
        successType = typeInfo<S>(),
        errorType = typeInfo<F>(),
        method = method,
        buildRequest = buildRequest
    )

    @InternalNomiAPI
    public fun <T> HttpRequestBuilder.appendParameter(
        name: String,
        value: T? = null,
        stringer: Stringer<T>
    ) {
        if (value != null) url.parameters.append(name, stringer.encode(value))
    }

    /**
     * Sets [binary] as the whole request body, streamed.
     *
     * It announces the binary's own [EndpointBinary.contentType], and its [EndpointBinary.size] as
     * `Content-Length` when known - an unknown size is what makes ktor fall back to
     * `Transfer-Encoding: chunked`, which is the honest answer for a stream of unknown length. The
     * source is written a buffer at a time rather than read into memory first.
     */
    @InternalNomiAPI
    public fun HttpRequestBuilder.setEndpointBinaryBody(binary: EndpointBinary) {
        setBody(
            ChannelWriterContent(
                body = { writeSource(binary.source()) },
                contentType = binary.contentType,
                contentLength = binary.size,
            )
        )
    }

    /**
     * Appends [file] as a file part named [key], carrying its file name, its size when known, and its
     * content type.
     *
     * It contributes `Content-Disposition` *parameters* only: ktor's `formData` writes the
     * `form-data; name="<key>"` itself and joins whatever a part adds onto it, so a disposition of our
     * own would land a second one in the middle of the header (SPECS.adoc § Gotchas).
     */
    @InternalNomiAPI
    public fun FormBuilder.appendEndpointFile(
        key: String,
        file: EndpointFile,
    ) {
        append(
            key = key,
            value = InputProvider(file.fileSize) { file.source() },
            headers = Headers.build {
                // ktor's formData already writes `Content-Disposition: form-data; name="<key>"` and
                // appends this as a further value of the same header, which MultiPartFormDataContent
                // then joins with "; ". So contribute *parameters only* - a disposition of our own
                // would put a second, or an empty, `form-data` token in the middle of the header.
                // The file name is always quoted: it is arbitrary user text, and every other client
                // quotes it. The size is a number, so it is always a bare token.
                append(HttpHeaders.ContentDisposition, buildList {
                    add("${ContentDisposition.Parameters.FileName}=${file.fileName.quote()}")
                    file.fileSize?.let { add("${ContentDisposition.Parameters.Size}=$it") }
                }.joinToString("; "))
                append(HttpHeaders.ContentType, file.contentType)
            }
        )
    }

    /**
     * Appends [value] as a value part named [key], encoded by [stringer] and announced with that
     * ``Stringer``'s [Stringer.contentType].
     *
     * Unlike [appendEndpointFile] it adds no `Content-Disposition` parameters at all: the
     * `form-data; name="<key>"` ktor writes is the whole of what a form field needs.
     */
    @InternalNomiAPI
    public fun <T> FormBuilder.appendEndpointItem(
        key: String,
        value: T,
        stringer: Stringer<T>,
    ) {
        // No Content-Disposition parameters: `form-data; name="<key>"`, which ktor writes itself, is the
        // whole of what a form field needs. (A `size` parameter here described the encoded string, which
        // is neither a thing the server reads nor meaningful for a form field.)
        append(
            key = key,
            value = stringer.encode(value),
            headers = Headers.build {
                append(HttpHeaders.ContentType, stringer.contentType)
            }
        )
    }

}


/**
 * An outgoing [EndpointService.Multipart] over a plain list of parts, which is what a generated
 * multipart builder returns.
 *
 * Single-pass in both directions: consuming one twice fails loudly rather than quietly sending an
 * empty body, so a builder's result is good for one call. Nothing here is released - the
 * ``EndpointFile``s in an outgoing part belong to the caller, unlike a part the server received.
 */
@InternalNomiAPI
public class ListMultipart<T : Any>(private val parts: List<T>) : EndpointService.Multipart<T> {

    private var index = 0
    private var exhausted = false

    override suspend fun readPart(): EndpointService.Multipart.Part<T>? {
        check(!exhausted) {
            "This Multipart has already been consumed. A Multipart is a single-pass stream, " +
                    "so build a new one for each call rather than reusing this one."
        }
        if (index == parts.size) {
            exhausted = true
            return null
        }
        // An outgoing part owns nothing to hand back: the EndpointFiles in it belong to the caller,
        // and closing them would break a file the caller still means to use.
        return EndpointService.Multipart.Part(parts[index++]) {}
    }
}
