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

@InternalNomiAPI
public interface NomiClientEndpointService : EndpointService {
    public val buildRequest: HttpRequestBuilder.() -> Unit
}

@InternalNomiAPI
@PublishedApi
context(service: NomiClientEndpointService)
internal suspend fun <S, F : Error> HttpClient.call(
    successType: TypeInfo,
    errorType: TypeInfo,
    pathSegments: List<String>,
    method: HttpMethod,
    buildRequest: suspend HttpRequestBuilder.() -> Unit = {}
): Either<S, F> {
    val statement = prepareRequest {
        url.pathSegments += pathSegments
        this.method = method
        buildRequest(this)
        service.buildRequest(this)
    }
    val response = try {
        statement.execute()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Throwable) {
        throw EndpointNetworkException(e.message, e)
    }
    if (response.status.isSuccess()) {
        if (response.status == HttpStatusCode.NoContent) {
            @Suppress("UNCHECKED_CAST")
            return (
                if (successType.type == Unit::class) unit<F>() as Either<S, F>
                else value(null as S)
            )
        }
        return value(response.body(successType))
    }
    if (response.status == HttpStatusCode.UnprocessableEntity) return error(response.body(errorType))
    throw EndpointStatusCodeException(response.status, response.bodyAsText())
}

@InternalNomiAPI
context(service: NomiClientEndpointService)
public suspend inline fun <reified S, reified F : Error> HttpClient.call(
    pathSegments: List<String>,
    method: HttpMethod,
    noinline buildRequest: suspend HttpRequestBuilder.() -> Unit = {}
): Either<S, F> = call(
    pathSegments = pathSegments,
    successType = typeInfo<S>(),
    errorType = typeInfo<F>(),
    method = method,
    buildRequest = buildRequest
)

@InternalNomiAPI
context(_: NomiClientEndpointService)
public fun <T> HttpRequestBuilder.appendParameter(
    name: String,
    value: T? = null,
    stringer: Stringer<T>
) {
    if (value != null) url.parameters.append(name, stringer.encode(value))
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

/**
 * Appends [file] as a file part named [key], carrying its file name, its size when known, and its
 * content type.
 *
 * It contributes `Content-Disposition` *parameters* only: ktor's `formData` writes the
 * `form-data; name="<key>"` itself and joins whatever a part adds onto it, so a disposition of our
 * own would land a second one in the middle of the header (SPECS.adoc § Gotchas).
 */
@InternalNomiAPI
context(_: NomiClientEndpointService)
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
context(_: NomiClientEndpointService)
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

