package io.nativeforge.core.common

/**
 * How hot the device is, in the steps Android reports (PowerManager's thermal status).
 * Long work backs off as it rises: streamed programs lower their frame rate and builds
 * run at a lower CPU priority, so the phone throttles less and stays usable.
 */
enum class ThermalLevel {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    ;

    /**
     * Java thread priority for the thread that starts build processes. On Android it maps to
     * the thread's nice value, which the child processes (make, ninja, clang) inherit.
     */
    val buildThreadPriority: Int
        get() = when (this) {
            NONE, LIGHT -> Thread.NORM_PRIORITY
            MODERATE -> 3
            SEVERE, CRITICAL -> Thread.MIN_PRIORITY
        }

    companion object {
        /** From Android's PowerManager.THERMAL_STATUS_* (0 none … 6 shutdown). */
        fun fromAndroidStatus(status: Int): ThermalLevel = when {
            status <= 0 -> NONE
            status == 1 -> LIGHT
            status == 2 -> MODERATE
            status == 3 -> SEVERE
            else -> CRITICAL
        }
    }
}
