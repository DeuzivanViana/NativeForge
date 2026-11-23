package io.nativeforge.core.common

/** Minimal logging abstraction so JVM modules do not depend on android.util.Log. */
interface Logger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, error: Throwable? = null)
    fun error(tag: String, message: String, error: Throwable? = null)
}

object NoOpLogger : Logger {
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, error: Throwable?) = Unit
    override fun error(tag: String, message: String, error: Throwable?) = Unit
}

object StdoutLogger : Logger {
    override fun debug(tag: String, message: String) = println("D/$tag: $message")
    override fun info(tag: String, message: String) = println("I/$tag: $message")
    override fun warn(tag: String, message: String, error: Throwable?) =
        println("W/$tag: $message${error?.let { " ($it)" }.orEmpty()}")
    override fun error(tag: String, message: String, error: Throwable?) =
        println("E/$tag: $message${error?.let { " ($it)" }.orEmpty()}")
}
