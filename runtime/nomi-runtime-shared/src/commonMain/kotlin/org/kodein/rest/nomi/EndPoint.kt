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
     * Registers [with] as the [Stringer] object to use for every [type]-typed path or query
     * parameter of the annotated resource, service, or annotation class.
     *
     * Applied to an annotation class, it turns that annotation into a carrier: the resolution walk
     * reads the annotations of a resource or service interface, the annotations on those annotation
     * classes, and so on to any depth, which is why a carrier needs `BINARY` or `RUNTIME`
     * retention to survive into the class file. The nearest declaration wins: [Stringed] on the
     * parameter itself, then the declaring interface and each interface outward, then the
     * [Stringer.Builtins]. Interface distance always dominates annotation depth (README.adoc §
     * Resolution order). Since [type] names a raw class, a parameterized type such as `List<Int>`
     * can only be given a `Stringer` through [Stringed].
     */
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
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

    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.CLASS)
    public annotation class MultipartFormData {
        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.CLASS)
        public annotation class Part(val name: String = "", val presence: PartPresence = PartPresence.Required)

        public enum class PartPresence {
            Optional, // Zero or one
            Required, // Exactly one
            RepeatedOptional, // Zero, one, or more
            RepeatedRequired, // One or more
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
 * A file, either one being uploaded or one that arrived as a part of a `multipart/form-data` body.
 *
 * A file read off a request belongs to the part it came from: [source] is valid only until that
 * part is released, which happens as soon as its `forEachPart` handler returns. Reading it after
 * that throws [IllegalStateException] — it would otherwise read from a buffer the server has
 * already handed back. A file you build yourself to upload is never closed.
 */
public class EndpointFile(
    public val fileName: String,
    public val fileSize: Long? = null,
    public val contentType: ContentType = ContentType.Application.OctetStream,
    source: () -> Source,
) {
    private val provider: () -> Source = source
    private var closed: Boolean = false

    /**
     * Opens the file's content.
     *
     * Throws [IllegalStateException] once the part this file came from has been released.
     */
    public val source: () -> Source = {
        check(!closed) {
            "EndpointFile '$fileName' was read outside of its part. " +
                "A file's content must be consumed inside forEachPart."
        }
        provider()
    }

    /** Ends this file's lifetime: every later [source] call fails. */
    @InternalNomiAPI
    public fun close() {
        closed = true
    }

    override fun toString(): String {
        return "EndpointFile(fileName=$fileName, fileSize=$fileSize, contentType=$contentType)"
    }
}
