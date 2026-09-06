package org.kodein.rest.nomi.demo.shared

import org.kodein.rest.nomi.Endpoint
import org.kodein.rest.nomi.Stringer
import org.kodein.rest.nomi.Stringer.Failure
import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.Either.Companion.value
import kotlin.uuid.Uuid


object UuidStringer : Stringer<Uuid> {
    override fun encode(value: Uuid): String = value.toString()
    override fun decode(url: String): Either<Uuid, Failure> =
        Uuid.parseOrNull(url)?.let { value(it) } ?: Either.Companion.error(Failure("Invalid UUID"))
}

@Endpoint.UseStringer(Uuid::class, UuidStringer::class)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class UseSharedStringers
