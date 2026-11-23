package io.nativeforge.core.common

/**
 * Result type used across module boundaries. Unlike [kotlin.Result] it carries a
 * typed, user-presentable [NfError] instead of an arbitrary Throwable.
 */
sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>
    data class Failure(val error: NfError) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): NfError? = (this as? Failure)?.error
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> = also {
    if (it is Outcome.Success) action(it.value)
}

inline fun <T> Outcome<T>.onFailure(action: (NfError) -> Unit): Outcome<T> = also {
    if (it is Outcome.Failure) action(it.error)
}

fun <T> T.asSuccess(): Outcome<T> = Outcome.Success(this)
fun NfError.asFailure(): Outcome<Nothing> = Outcome.Failure(this)

/**
 * Runs [block] and converts any thrown exception into an [Outcome.Failure].
 * Cancellation is never swallowed.
 */
inline fun <T> outcomeOf(
    errorFactory: (Throwable) -> NfError = { NfError.Unexpected(it.message ?: it.javaClass.simpleName, it) },
    block: () -> T,
): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Outcome.Failure(errorFactory(e))
}
