package io.nativeforge.core.common

/** Domain errors that can be shown to the user. Modules extend this hierarchy by composition. */
sealed class NfError(open val message: String, open val cause: Throwable? = null) {
    data class Io(override val message: String, override val cause: Throwable? = null) : NfError(message, cause)
    data class Network(override val message: String, override val cause: Throwable? = null) : NfError(message, cause)
    data class Validation(override val message: String) : NfError(message)
    data class NotFound(override val message: String) : NfError(message)
    data class ProcessFailed(override val message: String, val exitCode: Int) : NfError(message)
    data class Unsupported(override val message: String) : NfError(message)
    data class Unexpected(override val message: String, override val cause: Throwable? = null) : NfError(message, cause)
}
