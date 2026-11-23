package io.nativeforge.feature.assets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.nativeforge.core.assets.AssetKind
import io.nativeforge.core.assets.AssetKinds
import io.nativeforge.core.assets.Mesh
import io.nativeforge.core.assets.ModelLoader
import io.nativeforge.core.assets.TgaDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen viewer for images, sounds, 3D models and fonts in the project. */
@Composable
fun AssetViewerDialog(file: File, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(humanSize(file.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (AssetKinds.of(file)) {
                        AssetKind.IMAGE -> ImageViewer(file)
                        AssetKind.AUDIO -> AudioPlayer(file)
                        AssetKind.MODEL -> ModelViewer(file)
                        AssetKind.FONT -> FontViewer(file)
                        null -> Message("This file type has no viewer")
                    }
                }
            }
        }
    }
}

private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Ready<T>(val value: T) : Loaded<T>
    data class Failed(val message: String) : Loaded<Nothing>
}

@Composable
private fun ImageViewer(file: File) {
    val image by produceState<Loaded<Bitmap>>(Loaded.Loading, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (file.extension.equals("tga", ignoreCase = true)) {
                    val decoded = TgaDecoder.decode(file.readBytes())
                    Bitmap.createBitmap(decoded.argb, decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    // Keep huge textures within ~4096 px so they fit in memory.
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
                    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: error("Android cannot decode this image")
                }
            }.fold({ Loaded.Ready(it) }, { Loaded.Failed(it.message ?: "Could not open the image") })
        }
    }
    var pixelated by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    when (val state = image) {
        Loaded.Loading -> Message("Loading…")
        is Loaded.Failed -> Message(state.message)
        is Loaded.Ready -> Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().background(Color(0xFF202020))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.1f, 64f)
                            offset += pan
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Checkerboard()
                Image(
                    bitmap = state.value.asImageBitmap(),
                    contentDescription = file.name,
                    filterQuality = if (pixelated) FilterQuality.None else FilterQuality.Medium,
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
                )
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${state.value.width} × ${state.value.height}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                FilterChip(selected = pixelated, onClick = { pixelated = !pixelated }, label = { Text("Pixel art") })
                IconButton(onClick = { scale = 1f; offset = Offset.Zero }) { Icon(Icons.Filled.Refresh, contentDescription = "Reset zoom") }
            }
        }
    }
}

@Composable
private fun Checkerboard() {
    Canvas(Modifier.fillMaxSize()) {
        val cell = 12.dp.toPx()
        val columns = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (y in 0 until rows) for (x in 0 until columns) {
            if ((x + y) % 2 == 0) drawRect(Color(0xFF2C2C2C), Offset(x * cell, y * cell), Size(cell, cell))
        }
    }
}

@Composable
private fun AudioPlayer(file: File) {
    var error by remember { mutableStateOf<String?>(null) }
    val player = remember(file) {
        runCatching { MediaPlayer().apply { setDataSource(file.path); prepare() } }
            .onFailure { error = "Android cannot play this file (${it.message ?: it.javaClass.simpleName})" }
            .getOrNull()
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    val info = remember(file) {
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.path)
                listOfNotNull(
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let { "${it / 1000} kbit/s" },
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.let { "$it Hz" },
                ).joinToString(" · ")
            } finally {
                r.release()
            }
        }.getOrDefault("")
    }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var looping by remember { mutableStateOf(false) }
    LaunchedEffect(player, playing) {
        while (player != null && playing) {
            position = player.currentPosition.toFloat()
            if (!player.isPlaying && !player.isLooping) playing = false
            delay(100)
        }
    }
    if (player == null) return Message(error ?: "Could not open the sound")
    val duration = player.duration.coerceAtLeast(1).toFloat()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(time(position.toInt()) + " / " + time(duration.toInt()), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
        if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = position.coerceIn(0f, duration),
            onValueChange = { position = it; player.seekTo(it.toInt()) },
            valueRange = 0f..duration,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = {
                if (playing) player.pause() else player.start()
                playing = !playing
            }) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
            }
            FilterChip(selected = looping, onClick = { looping = !looping; player.isLooping = looping }, label = { Text("Loop") })
        }
    }
}

@Composable
private fun ModelViewer(file: File) {
    val mesh by produceState<Loaded<Mesh>>(Loaded.Loading, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { ModelLoader.load(file) }.fold({ Loaded.Ready(it) }, { Loaded.Failed(it.message ?: "Could not read the model") })
        }
    }
    var wireframe by remember { mutableStateOf(false) }
    when (val state = mesh) {
        Loaded.Loading -> Message("Loading model…")
        is Loaded.Failed -> Message(state.message)
        is Loaded.Ready -> Column(Modifier.fillMaxSize()) {
            val view = remember { mutableStateOf<ModelSurfaceView?>(null) }
            AndroidView(
                factory = { context -> ModelSurfaceView(context, state.value).also { view.value = it } },
                update = { it.setWireframe(wireframe) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${state.value.vertexCount} vertices · ${state.value.triangleCount} triangles\nDrag to rotate, pinch to zoom",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(selected = wireframe, onClick = { wireframe = !wireframe }, label = { Text("Wireframe") })
                IconButton(onClick = { view.value?.resetCamera() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reset view") }
            }
        }
    }
}

@Composable
private fun FontViewer(file: File) {
    val typeface = remember(file) { runCatching { Typeface.createFromFile(file) }.getOrNull() }
    if (typeface == null) return Message("Android cannot load this font")
    val family = remember(typeface) { FontFamily(typeface) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(12, 16, 24, 36, 56).forEach { size ->
            Text("The quick brown fox jumps over the lazy dog", fontFamily = family, fontSize = size.sp)
        }
        Text("ABCDEFGHIJKLMNOPQRSTUVWXYZ\nabcdefghijklmnopqrstuvwxyz\n0123456789 !?&@#%()[]{}<>+-*/=", fontFamily = family, fontSize = 20.sp)
        Text("Ação, coração, pão — ÁÉÍÓÚ ÂÊÔ ÃÕ Ç", fontFamily = family, fontSize = 20.sp, fontWeight = FontWeight.Normal)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Text("Glyphs: ${(0x20..0x7E).joinToString("") { it.toChar().toString() }}", fontFamily = family, fontSize = 18.sp)
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun time(millis: Int): String = "%d:%02d.%d".format(millis / 60000, millis / 1000 % 60, millis / 100 % 10)

private fun humanSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes bytes"
}
