package io.nativeforge.feature.editor

import kotlin.math.roundToInt

/**
 * Editor font sizes, in whole sp. Settings, keyboard zoom (Ctrl+= / Ctrl+wheel) and pinch zoom
 * all land on one of these, so the number in Settings is always the size the editor draws.
 */
object EditorFontSize {
    val RANGE = 10..24
    const val DEFAULT = 13

    /** The whole size in [RANGE] nearest to [sp]; a pinch usually ends between two steps. */
    fun snap(sp: Float): Int = if (sp.isNaN()) DEFAULT else sp.roundToInt().coerceIn(RANGE)

    /** One step larger ([delta] > 0) or smaller than [sp], kept inside [RANGE]. */
    fun step(sp: Float, delta: Int): Int = (snap(sp) + delta).coerceIn(RANGE)
}
