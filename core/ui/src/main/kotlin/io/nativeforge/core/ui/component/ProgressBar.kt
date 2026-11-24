package io.nativeforge.core.ui.component

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A plain linear progress bar: the filled part and the track only. Material 3's default draws a
 * gap between them and a "stop indicator" dot at the end of the track, which looked like a stray
 * blue point at the end of the bar.
 *
 * [progress] is 0..1; null shows the indeterminate animation.
 */
@Composable
fun ProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(modifier = modifier, gapSize = 0.dp)
    }
}
