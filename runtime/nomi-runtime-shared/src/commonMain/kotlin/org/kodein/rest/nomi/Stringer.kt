package org.kodein.rest.nomi

import org.kodein.rest.nomi.Either.Companion.error
import org.kodein.rest.nomi.Either.Companion.value


public interface Stringer<T> {
    public class Failure(public val reason: String) : Error

    public fun encode(value: T): String
    public fun decode(segment: String): Either<T, Failure>

    public object Builtins {

        public object BooleanStringer : Stringer<Boolean> {
            override fun encode(value: Boolean): String = if (value) "1" else "0"
            override fun decode(segment: String): Either<Boolean, Failure> =
                when (segment) {
                    "1", "true" -> value(true)
                    "0", "false" -> value(false)
                    else -> error(Failure("Invalid boolean"))
                }
        }

        public object IntStringer : Stringer<Int> {
            override fun encode(value: Int): String = value.toString()
            override fun decode(segment: String): Either<Int, Failure> =
                segment.toIntOrNull()?.let { value(it) } ?: error(Failure("Invalid integer number"))
        }

        public object LongStringer : Stringer<Long> {
            override fun encode(value: Long): String = value.toString()
            override fun decode(segment: String): Either<Long, Failure> =
                segment.toLongOrNull()?.let { value(it) } ?: error(Failure("Invalid long integer number"))
        }

    }
}

