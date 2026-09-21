@file:Suppress("unused")

package org.kodein.rest.nomi

import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.Either.Companion.error
import org.kodein.rest.nomi.Either.Companion.value
import kotlin.jvm.JvmInline
import kotlin.reflect.KClass
import kotlin.reflect.safeCast


/**
 * Marker interface for an endpoint's business failure: the `E` of an [Either].
 *
 * An implementation is serialized as the `422 Unprocessable Entity` body (README.adoc § Wire
 * protocol), so it must be serializable by the format the application registers on both sides.
 */
public interface Error

/**
 * The [Error] of an endpoint that cannot fail on business grounds.
 *
 * Its constructor is private, so a value can never be produced: an `Either<V, NoFail>` always holds
 * a value. A `when` exhausted over every other [Error] subtype can dispatch this impossible branch
 * to [isImpossible].
 */
public class NoFail private constructor() : Error

/** Always fails: a [NoFail] value cannot exist, so no call site can ever actually reach this. */
public fun NoFail.isImpossible(): Nothing = error("NoFail error $this")

/**
 * A type [T] captured as a [KClass], for the [Either] call sites that cannot reify it themselves.
 *
 * Obtain one from the `NType<T>()` factory rather than from this constructor.
 */
@JvmInline
public value class NType<T>(private val cls: KClass<*>) {
    /** [T]'s class, made non-nullable: `NType<Int?>` and `NType<Int>` both hold `Int::class`. */
    @Suppress("UNCHECKED_CAST")
    public val kClass: KClass<T & Any> get() = cls as KClass<T & Any>
}

/** Captures [T] as an [NType], reifying it at the call site. */
public inline fun <reified T> NType(): NType<T> = NType(T::class)

/**
 * What every endpoint returns: a value [V], or a business failure [E].
 *
 * One stored reference holds either branch, discriminated by `isInstance` - which is why [V] and
 * [E] must be disjoint types, a rule the processors enforce (SPECS.adoc § Endpoint return types).
 * Were [V] and [E] the same type, a value would also read as an error.
 *
 * Build one with `value()`, `unit()` or `error()`, and read it with the extensions in this file:
 * each comes both as an `inline` form reifying [V]/[E] from the call site, and as a plain form
 * taking explicit [NType] arguments, for the rare call site where the types cannot be reified.
 *
 * `Either` is a stopgap. Kotlin has no union types for errors yet, so `Either` stands in for one
 * until [KT-68296](https://youtrack.jetbrains.com/issue/KT-68296/Union-Types-for-Errors) lands, at
 * which point it will be replaced by a real union and removed from the library. Don't get too
 * attached, and don't build your own abstractions on top of it.
 */
@JvmInline
public value class Either<out V, out E : Error> private constructor(
    @PublishedApi
    internal val content: Any?
) {
    /** Holds the three factories: the only way to build an `Either`. */
    public companion object {
        /** An `Either` holding the successful [value]. */
        public fun <V, E : Error> value(value: V): Either<V, E> = Either(value)
        private val UnitValue: Either<Unit, Error> = Either(Unit)
        /** An `Either` holding `Unit`: `value(Unit)`, for the endpoints that return nothing. */
        @Suppress("UNCHECKED_CAST") public fun <E : Error> unit(): Either<Unit, E> = UnitValue as Either<Unit, E>
        /** An `Either` holding the business failure [error], answered to the client as a `422`. */
        public fun <V, E : Error> error(error: E): Either<V, E> = Either(error)
    }
}

/** Whether this holds a value. A `null` value counts as one: only an [Error] is a failure. */
public fun <V> Either<V, *>.isValue(valueType: NType<V>): Boolean = content == null || valueType.kClass.isInstance(content)
/** Whether this holds a business failure of type [E]. */
public fun <E : Error> Either<*, E>.isError(errorType: NType<E>): Boolean = errorType.kClass.isInstance(content)

/** Whether this holds a value. A `null` value counts as one: only an [Error] is a failure. */
public inline fun <reified V> Either<V, *>.isValue(): Boolean = content == null || V::class.isInstance(content)
/** Whether this holds a business failure of type [E]. */
public inline fun <reified E : Error> Either<*, E>.isError(): Boolean = E::class.isInstance(content)

/** The value, or `null` for a failure - indistinguishable from a value that is itself `null`. */
public fun <V> Either<V?, *>.valueOrNull(valueType: NType<V>): V? = content?.let { valueType.kClass.safeCast(it) }
/** The business failure, or `null` if this holds a value. */
public fun <E : Error> Either<*, E>.errorOrNull(errorType: NType<E>): E? = errorType.kClass.safeCast(content)

/** The value, or `null` for a failure - indistinguishable from a value that is itself `null`. */
public inline fun <reified V> Either<V?, *>.valueOrNull(): V? = content?.let { NType<V>().kClass.safeCast(it) }
/** The business failure, or `null` if this holds a value. */
public inline fun <reified E : Error> Either<*, E>.errorOrNull(): E? = E::class.safeCast(content)

/** The value, throwing [IllegalStateException] if this holds a failure. */
public fun <V> Either<V, *>.value(valueType: NType<V>): V = if (isValue(valueType)) @Suppress("UNCHECKED_CAST") (content as V) else error("Expected $valueType but was $content")
/** The business failure, throwing [IllegalStateException] if this holds a value. */
public fun <E : Error> Either<*, E>.error(errorType: NType<E>): E = if (isError(errorType)) @Suppress("UNCHECKED_CAST") (content as E) else error("Expected $errorType but was $content")

/** The value, throwing [IllegalStateException] if this holds a failure. */
public inline fun <reified V> Either<V, *>.value(): V = if (isValue()) content as V else error("Expected ${V::class} but was $content")
/** The business failure, throwing [IllegalStateException] if this holds a value. */
public inline fun <reified E : Error> Either<*, E>.error(): E = if (isError()) content as E else error("Expected ${E::class} but was $content")

/** Runs [block] on the value, if this holds one, and returns this `Either` unchanged. */
public fun <V, E : Error> Either<V, E>.onValue(valueType: NType<V>, block: (V) -> Unit): Either<V, E> = apply { if (isValue(valueType)) block(value(valueType)) }
/** Runs [block] on the business failure, if this holds one, and returns this `Either` unchanged. */
public fun <V, E : Error> Either<V, E>.onError(errorType: NType<E>, block: (E) -> Unit): Either<V, E> = apply { if (isError(errorType)) block(error(errorType)) }

/** Runs [block] on the value, if this holds one, and returns this `Either` unchanged. */
public inline fun <reified V, E : Error> Either<V, E>.onValue(block: (V) -> Unit): Either<V, E> = apply { if (isValue()) block(value()) }
/** Runs [block] on the business failure, if this holds one, and returns this `Either` unchanged. */
public inline fun <V, reified E : Error> Either<V, E>.onError(block: (E) -> Unit): Either<V, E> = apply { if (isError()) block(error()) }

/** Folds both branches down to one [T], applying [value] to a value and [error] to a failure. */
public fun <V, E : Error, T> Either<V, E>.on(valueType: NType<V>, errorType: NType<E>, value: (V) -> T, error: (E) -> T): T =
    when {
        isValue(valueType) -> value(value(valueType))
        isError(errorType) -> error(error(errorType))
        else -> error("Expected $valueType or $errorType but was $content")
    }

/** Folds both branches down to one [T], applying [value] to a value and [error] to a failure. */
public inline fun <reified V, reified E : Error, T> Either<V, E>.on(value: (V) -> T, error: (E) -> T): T =
    when {
        isValue() -> value(value())
        isError() -> error(error())
        else -> error("Expected ${V::class} or ${E::class} but was $content")
    }

/** The value, or [block] applied to the business failure. */
public fun <V, E : Error> Either<V, E>.valueOr(valueType: NType<V>, errorType: NType<E>, block: (E) -> V): V = if (isValue(valueType)) value(valueType) else block(error(errorType))

/** The value, or [block] applied to the business failure. */
public inline fun <reified V, reified E : Error> Either<V, E>.valueOr(block: (E) -> V): V = if (isValue()) value() else block(error())

/** Transforms the value with [transform], passing a business failure through unchanged. */
@Suppress("UNCHECKED_CAST")
public fun <V1, V2, E : Error> Either<V1, E>.mapValue(valueType: NType<V1>, transform: (V1) -> V2): Either<V2, E> =
    if (isValue(valueType)) value(transform(value(valueType))) else this as Either<V2, E>

/** Transforms the value with [transform], passing a business failure through unchanged. */
@Suppress("UNCHECKED_CAST")
public inline fun <reified V1, V2, E : Error> Either<V1, E>.mapValue(transform: (V1) -> V2): Either<V2, E> =
    if (isValue()) value(transform(value())) else this as Either<V2, E>

/** Transforms the business failure with [transform], passing a value through unchanged. */
@Suppress("UNCHECKED_CAST")
public fun <V, E1 : Error, E2 : Error> Either<V, E1>.mapError(errorType: NType<E1>, transform: (E1) -> E2): Either<V, E2> =
    if (isError(errorType)) error(transform(error(errorType))) else this as Either<V, E2>

/** Transforms the business failure with [transform], passing a value through unchanged. */
@Suppress("UNCHECKED_CAST")
public inline fun <V, reified E1 : Error, E2 : Error> Either<V, E1>.mapError(transform: (E1) -> E2): Either<V, E2> =
    if (isError()) error(transform(error())) else this as Either<V, E2>
