package io.nativeforge.core.remote.screen

import io.nativeforge.core.common.ThermalLevel

/** Frame rate cap and bit rate a streamed program's encoder aims for. */
data class StreamQuality(val frameRate: Int, val bitRateScale: Double) {

    /**
     * Bits per second for a display of [width] x [height] at this quality. Small displays
     * still get a share of [FLOOR_BIT_RATE] (fast motion needs it at any size).
     */
    fun bitRate(width: Int, height: Int): Int {
        val bySize = width.toLong() * height * frameRate * BITS_PER_PIXEL * bitRateScale
        return maxOf(bySize, FLOOR_BIT_RATE * bitRateScale).toLong().coerceIn(MIN_BIT_RATE.toLong(), MAX_BIT_RATE.toLong()).toInt()
    }

    companion object {
        const val MAX_FRAME_RATE = 60
        const val MIN_BIT_RATE = 300_000
        const val MAX_BIT_RATE = 10_000_000
        private const val FLOOR_BIT_RATE = 2_000_000.0
        private const val BITS_PER_PIXEL = 0.08

        /** Best first. Each step lowers the bit rate; some also halve the frame rate. */
        val LEVELS = listOf(
            StreamQuality(60, 1.0),
            StreamQuality(60, 0.7),
            StreamQuality(30, 0.6),
            StreamQuality(30, 0.4),
            StreamQuality(20, 0.3),
            StreamQuality(15, 0.2),
        )

        /** While nobody watches: the encoder keeps going (a viewer may join) but costs little. */
        val IDLE = StreamQuality(10, 0.1)

        /** The worst level a [thermal] state allows: a hot phone streams at 30 fps or less. */
        fun thermalFloor(thermal: ThermalLevel): Int = when (thermal) {
            ThermalLevel.NONE, ThermalLevel.LIGHT -> 0
            ThermalLevel.MODERATE -> 2
            ThermalLevel.SEVERE -> 4
            ThermalLevel.CRITICAL -> LEVELS.lastIndex
        }
    }
}

/**
 * Picks the stream's quality from how the network and the browsers keep up, and from how
 * hot the phone is. Steps down at once when a viewer falls behind (its queue grew too long
 * or its decoder dropped frames), at most every [downIntervalMillis]; steps back up one
 * level after [upAfterMillis] with short queues. Not thread safe: the caller synchronizes.
 */
class AdaptiveQuality(
    private val clock: () -> Long,
    private val downIntervalMillis: Long = 2_000,
    private val upAfterMillis: Long = 8_000,
) {
    /** Level chosen from the network alone (index into [StreamQuality.LEVELS]). */
    var networkLevel = 0
        private set

    var thermal = ThermalLevel.NONE
        private set

    private var lastDown = Long.MIN_VALUE
    private var calmSince = Long.MIN_VALUE

    val level: Int get() = maxOf(networkLevel, StreamQuality.thermalFloor(thermal))

    val current: StreamQuality get() = StreamQuality.LEVELS[level]

    /** True when the phone's heat, not the network, sets the current quality. */
    val limitedByHeat: Boolean get() = StreamQuality.thermalFloor(thermal) > networkLevel

    /** A viewer fell behind. True when the quality changed. */
    fun congested(): Boolean {
        val now = clock()
        calmSince = Long.MIN_VALUE
        if (lastDown != Long.MIN_VALUE && now - lastDown < downIntervalMillis) return false
        if (networkLevel == StreamQuality.LEVELS.lastIndex) return false
        return change { networkLevel++; lastDown = now }
    }

    /**
     * How long the slowest viewer's queue takes to send at the current bit rate. Long
     * queues count as falling behind; short ones for long enough raise the quality.
     */
    fun sample(queueDelayMillis: Long): Boolean {
        if (queueDelayMillis > HIGH_DELAY_MILLIS) return congested()
        if (queueDelayMillis > LOW_DELAY_MILLIS || networkLevel == 0) {
            calmSince = Long.MIN_VALUE
            return false
        }
        val now = clock()
        if (calmSince == Long.MIN_VALUE) calmSince = now
        val sinceDown = if (lastDown == Long.MIN_VALUE) Long.MAX_VALUE else now - lastDown
        if (now - calmSince < upAfterMillis || sinceDown < upAfterMillis) return false
        calmSince = now
        return change { networkLevel-- }
    }

    /** True when the quality changed. */
    fun thermalChanged(level: ThermalLevel): Boolean = change { thermal = level }

    private inline fun change(block: () -> Unit): Boolean {
        val before = level
        block()
        return level != before
    }

    companion object {
        const val HIGH_DELAY_MILLIS = 400L
        const val LOW_DELAY_MILLIS = 80L
    }
}
