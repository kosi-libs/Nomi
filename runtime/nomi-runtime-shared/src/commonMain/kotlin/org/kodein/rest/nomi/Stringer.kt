package org.kodein.rest.nomi

import org.kodein.rest.nomi.Either.Companion.error
import org.kodein.rest.nomi.Either.Companion.value


/**
 * The two-way conversion between a [T] and the `String` a path or query parameter is on the wire.
 *
 * An implementation must be an `object`, and is attached to a parameter by
 * [Endpoint.Stringed] or [Endpoint.UseStringer] - see the latter for the resolution order.
 * Escaping is not a `Stringer`'s job: the runtime percent-encodes every path segment and query
 * value on its way out and decodes it on the way back in, so [encode] and [decode] always work in
 * unescaped text.
 */
public interface Stringer<T> {
    /** Why [decode] could not read its input. [reason] becomes the `400 Bad Request` body. */
    public class Failure(public val reason: String) : Error

    /** Renders [value] as the text to put in a path segment or a query value. */
    public fun encode(value: T): String
    /** Reads [encoded] back into a [T], or fails with the reason the server answers `400` with. */
    public fun decode(encoded: String): Either<T, Failure>

    /**
     * The `Stringer` for each type Nomi handles out of the box.
     *
     * They sit at the lowest priority of the resolution order, so an application may register its
     * own `Stringer` for one of these types to override the built-in.
     */
    public object Builtins {

        /** Encodes to `"1"`/`"0"`; also decodes `"true"`/`"false"`. */
        public object BooleanStringer : Stringer<Boolean> {
            override fun encode(value: Boolean): String = if (value) "1" else "0"
            override fun decode(encoded: String): Either<Boolean, Failure> =
                when (encoded) {
                    "1", "true" -> value(true)
                    "0", "false" -> value(false)
                    else -> error(Failure("Invalid boolean"))
                }
        }

        /** Decimal, via [String.toIntOrNull]. */
        public object IntStringer : Stringer<Int> {
            override fun encode(value: Int): String = value.toString()
            override fun decode(encoded: String): Either<Int, Failure> =
                encoded.toIntOrNull()?.let { value(it) } ?: error(Failure("Invalid integer number"))
        }

        /** Decimal, via [String.toLongOrNull]. */
        public object LongStringer : Stringer<Long> {
            override fun encode(value: Long): String = value.toString()
            override fun decode(encoded: String): Either<Long, Failure> =
                encoded.toLongOrNull()?.let { value(it) } ?: error(Failure("Invalid long integer number"))
        }

        /** Passthrough: sent and received unchanged. */
        public object StringStringer : Stringer<String> {
            override fun encode(value: String): String = value
            override fun decode(encoded: String): Either<String, Failure> = value(encoded)
        }

    }
}

