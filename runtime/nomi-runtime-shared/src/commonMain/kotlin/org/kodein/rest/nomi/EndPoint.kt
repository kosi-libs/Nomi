package org.kodein.rest.nomi

import io.ktor.http.ContentType
import kotlinx.io.Source
import kotlin.reflect.KClass


/**
 * Marks a function of an [Endpoint.Service] interface as an endpoint, served over [method].
 *
 * The annotation always wins over the function's name, even one that looks like another verb.
 * Without it, the name must itself be exactly one of `get`, `post`, `put`, `patch` or `delete`
 * (SPECS.adoc § Endpoint functions). This annotation's nested members carry every other part of
 * Nomi's declaration vocabulary: [Resource], [Service], [Query], [Stringed] and [UseStringer].
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class Endpoint(val method: Method) {

    /** The HTTP method an endpoint is served over. Only these five are expressible. */
    public enum class Method {
        Get,
        Post,
        Put,
        Patch,
        Delete
    }

    /**
     * Marks an interface as a resource: one addressable node of the URL tree, whose `val`
     * properties are its path parameters.
     *
     * A root resource is declared top-level (or inside a plain declaration), a nested one directly
     * inside the resource interface it extends. The shared processor generates a `data class`
     * implementation and a factory for each, so a resource declares properties only - no functions,
     * no other nested declarations (SPECS.adoc § Resource declarations).
     *
     * [path] names this resource's own segments, relative to its parent, with each of its own path
     * parameters appearing exactly once as a `{placeholder}`. Left empty, it is derived: the simple
     * name with a lowercase first letter, followed by one segment per own path parameter, in
     * declaration order. Nomi routes are static, so ktor's `*`, `{...}`, `{name...}` and `{name?}`
     * syntaxes are all errors, and the runtime percent-encodes literal text on its way out
     * (SPECS.adoc § Resource paths).
     */
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.CLASS)
    public annotation class Resource(val path: String = "")

    /**
     * Marks an interface as a service: a set of endpoint functions, each an extension of a
     * [Resource] interface.
     *
     * The interface must extend [EndpointService] and declare nothing but endpoints. Endpoints are
     * collected across the whole annotated hierarchy, transitively, and no two of them - own or
     * inherited - may resolve to the same `(path, method)` pair (SPECS.adoc § Service
     * declarations). One service is implemented server-side by a
     * `@ServerEndpointService` class and reached client-side through a
     * `@ClientEndpointServiceFactory` object.
     */
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.CLASS)
    public annotation class Service

    /**
     * Marks an endpoint parameter as a query parameter rather than the request body.
     *
     * [name] overrides the parameter's own name on the wire. A non-nullable query parameter is
     * required - the server answers `400 Bad Request` when it is absent, so it may carry no default
     * value; a nullable one is optional, and its default applies on the client only, since the
     * server always passes either the decoded value or `null` (SPECS.adoc § Endpoint parameters).
     * Its type needs a [Stringer], resolved as described on [UseStringer].
     */
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.VALUE_PARAMETER)
    public annotation class Query(val name: String = "")

    /**
     * Registers [with] as the [Stringer] object to use for every [type]-typed path parameter, query
     * parameter or multipart part payload in scope of the annotated declaration - a resource, a
     * service, one endpoint function, a [MultipartFormData] part type, or an annotation class.
     *
     * Applied to an annotation class, it turns that annotation into a carrier: the resolution walk
     * reads the annotations of the declaration it starts at, the annotations on those annotation
     * classes, and so on to any depth, which is why a carrier needs `BINARY` or `RUNTIME`
     * retention to survive into the class file. The nearest declaration wins: [Stringed] on the
     * parameter itself, then the endpoint function, then the declaring interface and each interface
     * outward, then the [Stringer.Builtins]. Scope distance always dominates annotation depth
     * (README.adoc § Resolution order). Since [type] names a raw class, a parameterized type such
     * as `List<Int>` can only be given a `Stringer` through [Stringed].
     *
     * On an endpoint function it reaches that endpoint's query parameters and multipart parts, but
     * never its receiver resource's path parameters: a resource is analysed on its own, and its
     * generated factory has one encoding whichever endpoint addresses it.
     */
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
    @Repeatable
    public annotation class UseStringer(val type: KClass<*>, val with: KClass<out Stringer<*>>)

    /**
     * Pins [with] as the [Stringer] object for this one resource path property or endpoint query
     * parameter, ahead of every [UseStringer] registration.
     *
     * [with] must be an `object` implementing `Stringer<T>` for exactly the declared type,
     * nullability stripped for an optional query parameter (SPECS.adoc § Stringers). The request
     * body has no `Stringer` - it is (de)serialized as a whole - so this annotation on a body
     * parameter is an error.
     */
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
    public annotation class Stringed(val with: KClass<out Stringer<*>>)

    /**
     * Marks a `sealed interface` as a part type: the closed set of parts a `multipart/form-data`
     * body may carry, one per nested subtype.
     *
     * Each part is a `data class` nested inside the part type, declaring exactly one `val` - its
     * payload. An [EndpointFile] payload makes a file part; any other type makes a value part, and
     * needs a [Stringer] resolved as a query parameter's is, starting at the part type itself
     * (SPECS.adoc § Multipart part types). A part type is named by an endpoint's
     * [EndpointService.Multipart] parameter, and nowhere else.
     */
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.CLASS)
    public annotation class MultipartFormData {

        /**
         * Overrides one part's name on the wire, and how many times it may occur.
         *
         * [name] replaces the default, which is the subtype's own simple name with a lowercase
         * first letter; it must match the same pattern a query parameter name must. [presence]
         * fixes the part's cardinality, shaping the generated builder's parameter and what the
         * server enforces - see [PartPresence].
         *
         * The annotation is optional: an unannotated part takes a derived name and
         * [PartPresence.Required].
         */
        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.CLASS)
        public annotation class Part(val name: String = "", val presence: PartPresence = PartPresence.Required)

        /**
         * How many times a part may occur in one body.
         *
         * It drives both ends: the generated client builder takes a single value, a nullable one,
         * or a `List`; the server answers `400 Bad Request` for a body that breaks the count
         * (README.adoc § Multipart form data).
         */
        public enum class PartPresence {
            /** Zero or one. The builder takes `T? = null`; a second occurrence is a `400`. */
            Optional,

            /** Exactly one. The builder takes `T`; absent, or a second occurrence, is a `400`. */
            Required,

            /** Zero, one, or more. The builder takes `List<T> = emptyList()`; never a count error. */
            RepeatedOptional,

            /** One or more. The builder takes `List<T>`; absent is a `400`. */
            RepeatedRequired;

            /** Whether the part must occur at least once: the server answers `400` if it never does. */
            public val isRequired: Boolean get() = this == Required || this == RepeatedRequired

            /** Whether the part may occur more than once: the server answers `400` on a second one if not. */
            public val isRepeated: Boolean get() = this == RepeatedOptional || this == RepeatedRequired
        }
    }
}

/**
 * Supertype of every [Endpoint.Service] interface, and the receiver the runtime's context-dependent
 * functions require: it is what makes `httpError` and `routingCall` callable from inside an
 * endpoint implementation, and nowhere else.
 */
public interface EndpointService {

    /**
     * The parts of a `multipart/form-data` body, as the values of [T].
     *
     * A multipart body is a single-pass stream: every part is read once, in the order the peer sent
     * it. [forEachPart] is the only supported way to consume one — it owns the loop so that reaching
     * the end of the stream, and leaving it early, are both accounted for.
     */
    public interface Multipart<T : Any> {

        /**
         * A part that has been read and decoded, together with the means to release it.
         *
         * [close] releases whatever the part holds — the peer's buffers, and every [EndpointFile]
         * decoded out of it. [forEachPart] calls it as soon as the handler returns, so a part's
         * content is only valid for the duration of that handler.
         */
        @InternalNomiAPI
        public class Part<out T>(
            public val value: T,
            public val close: suspend () -> Unit,
        )

        /** Reads and decodes the next part, or `null` once the stream is exhausted. */
        @InternalNomiAPI
        public suspend fun readPart(): Part<T>?

        /**
         * Called by [forEachPart] once the stream has been read to its end, to check that every
         * part the endpoint requires was actually sent. Answers `400` if one was not.
         */
        @InternalNomiAPI
        public suspend fun validateRequiredParts() {}

        /**
         * Called by [forEachPart] when consumption stopped before the end of the stream — the
         * handler returned early, or threw. Releases whatever is left unread.
         *
         * It deliberately does *not* check for missing parts: leaving early is a decision the
         * endpoint made, not a malformed request.
         */
        @InternalNomiAPI
        public suspend fun discardRemaining() {}
    }

}

/**
 * Runs [handler] over every part, in the order the peer sent them. This is the supported way to
 * consume a [EndpointService.Multipart] - it owns the loop, so that reaching the end of the stream
 * and leaving it early are both accounted for.
 *
 * Whichever way the loop ends, the stream is accounted for: read it through and the parts the
 * endpoint requires are checked, answering `400 Bad Request` for a missing one; leave early -
 * `return`, `break`, or a throw - and what is left is released instead, with no missing-part
 * check, since leaving early is a decision the endpoint made rather than a malformed request.
 *
 * Because it is `inline`, [handler] may suspend, and may `return` from the enclosing endpoint:
 *
 * ```kotlin
 * parts.forEachPart { part ->
 *     when (part) {
 *         is Upload.File -> if (part.file.fileSize > MAX) return error(TooLarge)
 *         is Upload.From -> from = part.from
 *     }
 * }
 * ```
 *
 * **A part is released as soon as its handler returns.** Its content - an [EndpointFile] above all
 * - is only valid inside [handler]; reading one afterwards throws. Copy out whatever must outlive
 * the loop while you are still in it (README.adoc § Part lifetime).
 */
@OptIn(InternalNomiAPI::class)
public suspend inline fun <T : Any> EndpointService.Multipart<T>.forEachPart(handler: (T) -> Unit) {
    var readToEnd = false
    try {
        while (true) {
            val part = readPart() ?: break
            try {
                handler(part.value)
            } finally {
                part.close()
            }
        }
        readToEnd = true
    } finally {
        if (readToEnd) validateRequiredParts()
        else discardRemaining()
    }
}


/**
 * Opts in to Nomi's internals: declarations that generated code calls, whose signatures may change
 * with any release.
 *
 * Nothing annotated with this is meant to be written by hand.
 */
@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class InternalNomiAPI

/**
 * Bytes travelling over an endpoint, with the content type and - when known - the length that
 * describe them.
 *
 * Its two forms are not interchangeable, and Nomi rejects each in the other's position:
 * [EndpointFile] is one part of a `multipart/form-data` body and carries a file name, while
 * [EndpointBinary] is a whole raw body - a request's or a response's.
 *
 * Each form declares its own `source`, a provider rather than a value, so nothing is read until
 * something asks. They differ in one way that matters: ``EndpointBinary``'s is `suspend`, so it can
 * pull from a live channel, while ``EndpointFile``'s is not, because ktor's multipart builder takes
 * a plain provider. Both share a lifetime: [close] ends it, and every later read then throws rather
 * than reading a buffer that has already been handed back.
 */
public sealed class AbstractEndpointBinary(
    public val size: Long? = null,
    public val contentType: ContentType = ContentType.Application.OctetStream,
) {
    private var closed: Boolean = false

    /** Throws [IllegalStateException] once this has been [close]d - for a multipart file, when its
     * part was released; for a body, when the endpoint or the streaming handler returned. */
    protected fun checkOpen() {
        check(!closed) { closedErrorMessage() }
    }

    protected abstract fun closedErrorMessage(): String

    /** Ends this content's lifetime: every later read fails. */
    @InternalNomiAPI
    public fun close() {
        closed = true
    }
}


/**
 * A file, either one being uploaded or one that arrived as a part of a `multipart/form-data` body.
 *
 * A file read off a request belongs to the part it came from: [source] is valid only until that
 * part is released, which happens as soon as its `forEachPart` handler returns. Reading it after
 * that throws [IllegalStateException] — it would otherwise read from a buffer the server has
 * already handed back. A file you build yourself to upload is never closed.
 *
 * [source] is deliberately not `suspend`, unlike ``EndpointBinary``'s: ktor's `InputProvider` takes
 * a plain `() -> Source`, and a suspending one could not be handed to it without buffering every
 * upload into memory first.
 */
public class EndpointFile(
    public val fileName: String,
    fileSize: Long? = null,
    contentType: ContentType = ContentType.Application.OctetStream,
    source: () -> Source,
) : AbstractEndpointBinary(fileSize, contentType) {
    private val provider: () -> Source = source

    /** Opens the file's content. */
    public val source: () -> Source = {
        checkOpen()
        provider()
    }

    public val fileSize: Long? get() = super.size

    override fun closedErrorMessage(): String =
        "EndpointFile '$fileName' was read outside of its part. A file's content must be consumed inside forEachPart."

    override fun toString(): String {
        return "EndpointFile(fileName=$fileName, fileSize=$fileSize, contentType=$contentType)"
    }
}

/**
 * A whole raw body, streamed - a request's when an endpoint takes one as its body parameter, a
 * response's when an endpoint returns one.
 *
 * Whichever way it travels, the sender announces this [contentType] and, when [size] is known, a
 * matching `Content-Length` - a `null` size goes out `Transfer-Encoding: chunked`. Nothing is
 * serialized and `ContentNegotiation` is not consulted (README.adoc § Streaming a request body,
 * § Streaming a response).
 *
 * One received is single-pass and outlives nothing: opening [source] twice throws, and so does
 * reading it once its scope has ended - the endpoint returning, for a request body, or the
 * `callStreaming` handler returning, for a response one. That is why a binary-returning endpoint
 * can only be called through `callStreaming`: a plain `call` closes the response before handing
 * anything back. For one part of a multipart body, use [EndpointFile] instead.
 */
public class EndpointBinary(
    size: Long? = null,
    contentType: ContentType = ContentType.Application.OctetStream,
    source: suspend () -> Source,
) : AbstractEndpointBinary(size, contentType) {
    private val provider: suspend () -> Source = source

    /** Opens the content. `suspend` so that it can pull from a channel that has not arrived yet. */
    public val source: suspend () -> Source = {
        checkOpen()
        provider()
    }

    override fun closedErrorMessage(): String =
        "EndpointBinary was read after being closed."

    override fun toString(): String {
        return "EndpointBinary(size=$size, contentType=$contentType)"
    }
}
