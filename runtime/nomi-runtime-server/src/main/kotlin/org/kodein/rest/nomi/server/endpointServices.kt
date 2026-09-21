package org.kodein.rest.nomi.server

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.EndpointFile
import org.kodein.rest.nomi.Endpoint.MultipartFormData.PartPresence
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.Error
import org.kodein.rest.nomi.InternalNomiAPI
import org.kodein.rest.nomi.LightException
import org.kodein.rest.nomi.Stringer
import org.kodein.rest.nomi.onError
import org.kodein.rest.nomi.onValue
import org.kodein.rest.nomi.valueOr
import kotlin.coroutines.CoroutineContext


@PublishedApi
internal class EndpointStatusCodeException(val code: HttpStatusCode, message: String?) : LightException(message)

/**
 * Aborts the endpoint with a plain HTTP status: the client reads it back as a
 * `CallStatus.ServerFailure`, with [message] as the response body when one is given.
 *
 * Callable only from inside an endpoint implementation, which is what the [EndpointService] context
 * enforces. [code] must not be `422 Unprocessable Entity`: Nomi reserves that status for the
 * business failure an endpoint returns as its `Either`'s error (README.adoc § Wire protocol), so
 * passing it fails instead.
 */
context(_: EndpointService)
public fun httpError(code: HttpStatusCode, message: String? = null): Nothing {
    require(code != HttpStatusCode.UnprocessableEntity) { "Cannot use UnprocessableEntity as an error code as it is reserved by Nomi." }
    throw EndpointStatusCodeException(code, message)
}

@PublishedApi
internal class RoutingCallElement(val call: RoutingCall) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key
    companion object Key : CoroutineContext.Key<RoutingCallElement>
}

/**
 * The ktor [RoutingCall] behind the endpoint currently running: headers, remote host, cookies -
 * everything about the request that Nomi does not already surface as a path or query parameter.
 *
 * Callable only from inside an endpoint implementation, which is what the [EndpointService] context
 * enforces; it reads the call from the coroutine context the generated route puts it in, and so
 * fails if called from a coroutine that does not inherit that context.
 */
context(_: EndpointService)
public suspend fun routingCall(): RoutingCall =
    currentCoroutineContext()[RoutingCallElement]?.call
        ?: error("No RoutingCall in coroutineContext")

@InternalNomiAPI
public suspend inline fun <T : EndpointService, reified S, reified F : Error> RoutingCall.respond(service: T, noinline block: suspend T.() -> Either<S, F>) {
    withContext(RoutingCallElement(this)) {
        try {
            service.block()
                .onValue { success ->
                    if (success is Unit || success == null) respond(HttpStatusCode.NoContent)
                    else this@respond.respond(status = HttpStatusCode.OK, message = success, typeInfo<S>())
                }
                .onError { failure ->
                    this@respond.respond(status = HttpStatusCode.UnprocessableEntity, message = failure, typeInfo<F>())
                }
        } catch (e: EndpointStatusCodeException) {
            if (e.message == null) respond(e.code)
            else respond(e.code, e.message!!)
        }
    }
}

@InternalNomiAPI
context(_: EndpointService)
public inline fun <reified T> decodeOrNull(stringer: Stringer<T>, encoded: String?): T? {
    if (encoded == null) return null
    return decode(stringer, encoded)
}

@InternalNomiAPI
context(_: EndpointService)
public inline fun <reified T> decode(stringer: Stringer<T>, encoded: String): T {
    return stringer.decode(encoded).valueOr { httpError(HttpStatusCode.BadRequest, it.reason) }
}

@InternalNomiAPI
context(_: EndpointService)
public inline fun <reified T> decodeRequired(stringer: Stringer<T>, name: String, encoded: String?): T {
    if (encoded == null) httpError(HttpStatusCode.BadRequest, "Missing required query parameter '$name'")
    return decode(stringer, encoded)
}

/**
 * The scope a part is decoded in: it owns every [EndpointFile] decoded out of that part, so that
 * releasing the part ends their lifetime too.
 *
 * It is the receiver of [EndpointMultipartReceiver]'s `transform`, which is what makes
 * [asEndpointFile] callable there and nowhere else.
 */
@InternalNomiAPI
public class PartScope(
    private val service: EndpointService,
) {
    private val files = mutableListOf<EndpointFile>()

    public fun PartData.asEndpointFile(name: String): EndpointFile {
        this as? PartData.FileItem ?: context(service) { httpError(HttpStatusCode.BadRequest, "Expected part '$name' to be File, but was ${this::class.simpleName}") }
        return EndpointFile(
            fileName = contentDisposition?.parameter(ContentDisposition.Parameters.FileName) ?: context(service) { httpError(HttpStatusCode.BadRequest, "File part '$name' does not have a filename") },
            fileSize = contentDisposition?.parameter(ContentDisposition.Parameters.Size)?.toLongOrNull(),
            contentType = contentType ?: ContentType.Application.OctetStream,
            source = { provider().asSource().buffered() }
        ).also { files += it }
    }

    /** Ends the lifetime of every file decoded in this scope. */
    @InternalNomiAPI
    public fun closeFiles() {
        files.forEach { it.close() }
    }
}

/**
 * Decodes a part's content through its [Stringer].
 *
 * Unlike [PartScope.asEndpointFile] this stays top-level: the decoded value is a plain immutable
 * value that owns nothing, so it has no lifetime to tie to its part. It is `reified` because
 * `Either` discriminates its value from its error by runtime type.
 *
 * A part's `Content-Type` is advisory, so only an explicitly incompatible one is rejected — see
 * [matchesPartContentType].
 */
@InternalNomiAPI
context(_: EndpointService)
public inline fun <reified T> PartData.asEndpointValue(name: String, stringer: Stringer<T>): T {
    this as? PartData.FormItem ?: httpError(HttpStatusCode.BadRequest, "Expected part '$name' to be Form Item, but was ${this::class.simpleName}")
    if (!matchesPartContentType(stringer.contentType)) httpError(HttpStatusCode.BadRequest, "Expected part '$name' content type to be ${stringer.contentType}, but was $contentType")
    return stringer.decode(value).valueOr { httpError(HttpStatusCode.BadRequest, "Could not decode part '$name': ${it.reason}") }
}

/**
 * Whether this part's declared `Content-Type` is compatible with [expected].
 *
 * A part is not obliged to declare one — RFC 7578 makes the header optional, and a browser omits it
 * for an ordinary form field — so an absent `Content-Type` contradicts nothing and is accepted; if
 * the content is then wrong, the [Stringer] rejects it with a far better message than a header
 * mismatch would give.
 *
 * A declared one is matched with [ContentType.match], so parameters such as `charset=utf-8` are
 * ignored and a [Stringer] may legitimately declare `*` as its type or subtype.
 */
@InternalNomiAPI
public fun PartData.matchesPartContentType(expected: ContentType): Boolean {
    val declared = contentType ?: return true
    return declared.match(expected)
}

/**
 * Reads a `multipart/form-data` body as the values of [T], enforcing the cardinality each part was
 * declared with.
 *
 * [parts] maps every part name the endpoint declares to its [PartPresence]; a name absent from it
 * is unknown to the endpoint, and is [transform]'s to reject.
 */
@InternalNomiAPI
public class EndpointMultipartReceiver<T : Any>(
    private val service: EndpointService,
    private val multipart: MultiPartData,
    private val parts: Map<String, PartPresence>,
    private val transform: suspend PartScope.(PartData) -> T
) : EndpointService.Multipart<T> {

    private val counts = mutableMapOf<String, Int>()
    private var exhausted = false

    override suspend fun readPart(): EndpointService.Multipart.Part<T>? {
        val part = multipart.readPart() ?: run {
            exhausted = true
            return null
        }
        val partName = part.name ?: context(service) { httpError(HttpStatusCode.BadRequest, "Unsupported part without name") }
        val count = (counts[partName] ?: 0) + 1
        counts[partName] = count
        // An unknown name has no declared presence; rejecting it is transform's job, not ours.
        val presence = parts[partName]
        if (count > 1 && presence != null && !presence.isRepeated) {
            context(service) { httpError(HttpStatusCode.BadRequest, "Part '$partName' must not be repeated") }
        }
        val scope = PartScope(service)
        return EndpointService.Multipart.Part(
            value = scope.transform(part),
            close = {
                scope.closeFiles()
                part.release()
            },
        )
    }

    override suspend fun validateRequiredParts() {
        parts.forEach { (name, presence) ->
            if (presence.isRequired && name !in counts) {
                context(service) { httpError(HttpStatusCode.BadRequest, "Missing required part '$name'") }
            }
        }
    }

    override suspend fun discardRemaining() {
        if (exhausted) return
        while (true) {
            val part = multipart.readPart() ?: break
            part.release()
        }
        exhausted = true
    }
}

