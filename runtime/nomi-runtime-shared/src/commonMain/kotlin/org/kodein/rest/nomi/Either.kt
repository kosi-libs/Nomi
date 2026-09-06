@file:Suppress("unused")

package org.kodein.rest.nomi

import org.kodein.rest.nomi.Either.Companion.error
import org.kodein.rest.nomi.Either.Companion.value
import kotlin.jvm.JvmInline
import kotlin.reflect.KClass
import kotlin.reflect.safeCast


public interface Error
public class NoFail private constructor() : Error

public fun NoFail.isImpossible(): Nothing = error("NoFail error $this")

// Will be advantageously replaced by Union for Errors: https://youtrack.jetbrains.com/issue/KT-68296/Union-Types-for-Errors
// As soon as Union is available, this will be replaced by Union, so don't get too attached!
// WARNING: V and E must be different types. If V == E, then this will be Value AND Error!
@JvmInline
public value class Either<out V, out E : Error> private constructor(
    @PublishedApi
    internal val content: Any?
) {
    public companion object {
        public fun <V, E : Error> value(value: V): Either<V, E> = Either(value)
        public fun <E : Error> unit(): Either<Unit, E> = Either(Unit)
        public fun <V, E : Error> error(error: E): Either<V, E> = Either(error)
    }
}

public fun <V> Either<V, *>.isValue(valueClass: KClass<V & Any>): Boolean = content == null || valueClass.isInstance(content)
public fun <E : Error> Either<*, E>.isError(errorClass: KClass<E>): Boolean = errorClass.isInstance(content)

public inline fun <reified V> Either<V, *>.isValue(): Boolean = content == null || V::class.isInstance(content)
public inline fun <reified E : Error> Either<*, E>.isError(): Boolean = E::class.isInstance(content)

public fun <V : Any> Either<V?, *>.valueOrNull(valueClass: KClass<V>): V? = content?.let { valueClass.safeCast(it) }
public fun <E : Error> Either<*, E>.errorOrNull(errorClass: KClass<E>): E? = errorClass.safeCast(content)

public inline fun <reified V : Any> Either<V?, *>.valueOrNull(): V? = content?.let { V::class.safeCast(it) }
public inline fun <reified E : Error> Either<*, E>.errorOrNull(): E? = E::class.safeCast(content)

public fun <V> Either<V, *>.value(valueClass: KClass<V & Any>): V = if (isValue(valueClass)) @Suppress("UNCHECKED_CAST") (content as V) else error("Expected $valueClass but was $content")
public fun <E : Error> Either<*, E>.error(errorClass: KClass<E>): E = if (isError(errorClass)) @Suppress("UNCHECKED_CAST") (content as E) else error("Expected $errorClass but was $content")

public inline fun <reified V> Either<V, *>.value(): V = if (isValue()) content as V else error("Expected ${V::class} but was $content")
public inline fun <reified E : Error> Either<*, E>.error(): E = if (isError()) content as E else error("Expected ${E::class} but was $content")

public fun <V, E : Error> Either<V, E>.onValue(valueClass: KClass<V & Any>, block: (V) -> Unit): Either<V, E> = apply { if (isValue(valueClass)) block(value(valueClass)) }
public fun <V, E : Error> Either<V, E>.onError(errorClass: KClass<E>, block: (E) -> Unit): Either<V, E> = apply { if (isError(errorClass)) block(error(errorClass)) }

public inline fun <reified V, E : Error> Either<V, E>.onValue(block: (V) -> Unit): Either<V, E> = apply { if (isValue()) block(value()) }
public inline fun <V, reified E : Error> Either<V, E>.onError(block: (E) -> Unit): Either<V, E> = apply { if (isError()) block(error()) }

public fun <V, E : Error, T> Either<V, E>.on(valueClass: KClass<V & Any>, errorClass: KClass<E>, value: (V) -> T, error: (E) -> T): T =
    when {
        isValue(valueClass) -> value(value(valueClass))
        isError(errorClass) -> error(error(errorClass))
        else -> error("Expected $valueClass or $errorClass but was $content")
    }

public inline fun <reified V, reified E : Error, T> Either<V, E>.on(value: (V) -> T, error: (E) -> T): T =
    when {
        isValue() -> value(value())
        isError() -> error(error())
        else -> error("Expected ${V::class} or ${E::class} but was $content")
    }

public fun <V, E : Error> Either<V, E>.valueOr(valueClass: KClass<V & Any>, errorClass: KClass<E>, block: (E) -> V): V = if (isValue(valueClass)) value(valueClass) else block(error(errorClass))

public inline fun <reified V, reified E : Error> Either<V, E>.valueOr(block: (E) -> V): V = if (isValue()) value() else block(error())

@Suppress("UNCHECKED_CAST")
public fun <V1, V2, E : Error> Either<V1, E>.mapValue(valueClass: KClass<V1 & Any>, transform: (V1) -> V2): Either<V2, E> =
    if (isValue(valueClass)) value(transform(value(valueClass))) else this as Either<V2, E>

@Suppress("UNCHECKED_CAST")
public inline fun <reified V1, V2, E : Error> Either<V1, E>.mapValue(transform: (V1) -> V2): Either<V2, E> =
    if (isValue()) value(transform(value())) else this as Either<V2, E>

@Suppress("UNCHECKED_CAST")
public fun <V, E1 : Error, E2 : Error> Either<V, E1>.mapError(errorClass: KClass<E1>, transform: (E1) -> E2): Either<V, E2> =
    if (isError(errorClass)) error(transform(error(errorClass))) else this as Either<V, E2>

@Suppress("UNCHECKED_CAST")
public inline fun <V, reified E1 : Error, E2 : Error> Either<V, E1>.mapError(transform: (E1) -> E2): Either<V, E2> =
    if (isError()) error(transform(error())) else this as Either<V, E2>
