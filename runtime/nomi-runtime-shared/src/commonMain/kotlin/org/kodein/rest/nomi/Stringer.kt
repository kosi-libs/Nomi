package org.kodein.rest.nomi

import org.kodein.rest.nomi.Either.Companion.error
import org.kodein.rest.nomi.Either.Companion.value


public interface Stringer<T> {
    public class Failure(public val reason: String) : Error

    public fun encode(value: T): String
    public fun decode(url: String): Either<T, Failure>

    public object Builtins {

        public object BooleanStringer : Stringer<Boolean> {
            override fun encode(value: Boolean): String = if (value) "1" else "0"
            override fun decode(url: String): Either<Boolean, Failure> =
                value(url == "1" || url.lowercase() == "true")
        }

        public object IntStringer : Stringer<Int> {
            override fun encode(value: Int): String = value.toString()
            override fun decode(url: String): Either<Int, Failure> =
                url.toIntOrNull()?.let { value(it) } ?: error(Failure("Invalid integer number"))
        }

        public object LongStringer : Stringer<Long> {
            override fun encode(value: Long): String = value.toString()
            override fun decode(url: String): Either<Long, Failure> =
                url.toLongOrNull()?.let { value(it) } ?: error(Failure("Invalid long integer number"))
        }

    }
}

