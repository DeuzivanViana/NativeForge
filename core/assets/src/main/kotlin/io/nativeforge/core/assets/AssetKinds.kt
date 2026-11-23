package io.nativeforge.core.assets

import java.io.File

/** Files the asset viewer can show instead of the code editor. */
enum class AssetKind { IMAGE, AUDIO, MODEL, FONT }

object AssetKinds {
    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "tga", "ico")
    private val AUDIO = setOf("wav", "ogg", "mp3", "flac", "m4a", "aac", "opus", "mid", "midi")
    private val MODEL = setOf("obj", "stl", "gltf", "glb", "ply")
    private val FONT = setOf("ttf", "otf")

    fun of(file: File): AssetKind? = when (file.extension.lowercase()) {
        in IMAGE -> AssetKind.IMAGE
        in AUDIO -> AssetKind.AUDIO
        in MODEL -> AssetKind.MODEL
        in FONT -> AssetKind.FONT
        else -> null
    }
}
