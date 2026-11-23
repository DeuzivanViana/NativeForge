package io.nativeforge.core.assets

/** Decoded image as ARGB pixels, row by row from the top. */
class DecodedImage(val width: Int, val height: Int, val argb: IntArray)

/**
 * TGA images (common in game assets; Android cannot decode them): true-color 24/32-bit
 * and 8-bit grayscale, raw or RLE-compressed, either row order.
 */
object TgaDecoder {
    fun decode(bytes: ByteArray): DecodedImage {
        require(bytes.size >= 18) { "Not a TGA file" }
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        val idLength = u8(0)
        val colorMapType = u8(1)
        val type = u8(2)
        val width = u16(12)
        val height = u16(14)
        val bpp = u8(16)
        val descriptor = u8(17)
        require(colorMapType == 0) { "Color-mapped TGA files are not supported" }
        require(type in setOf(2, 3, 10, 11)) { "Unsupported TGA type $type" }
        require(width in 1..16384 && height in 1..16384) { "Invalid TGA size" }
        val bytesPerPixel = bpp / 8
        require(bytesPerPixel in setOf(1, 3, 4)) { "Unsupported TGA depth $bpp" }
        val colorMapLength = u16(5) * ((u8(7) + 7) / 8)
        var offset = 18 + idLength + colorMapLength
        val pixels = IntArray(width * height)

        fun readPixel(at: Int): Int = when (bytesPerPixel) {
            1 -> u8(at).let { g -> (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
            3 -> (0xFF shl 24) or (u8(at + 2) shl 16) or (u8(at + 1) shl 8) or u8(at)
            else -> (u8(at + 3) shl 24) or (u8(at + 2) shl 16) or (u8(at + 1) shl 8) or u8(at)
        }

        var index = 0
        if (type == 2 || type == 3) {
            require(offset + pixels.size * bytesPerPixel <= bytes.size) { "Truncated TGA file" }
            while (index < pixels.size) { pixels[index++] = readPixel(offset); offset += bytesPerPixel }
        } else {
            while (index < pixels.size) {
                require(offset < bytes.size) { "Truncated TGA file" }
                val header = u8(offset++)
                val count = (header and 0x7F) + 1
                if (header and 0x80 != 0) {
                    require(offset + bytesPerPixel <= bytes.size) { "Truncated TGA file" }
                    val pixel = readPixel(offset)
                    offset += bytesPerPixel
                    repeat(count) { if (index < pixels.size) pixels[index++] = pixel }
                } else {
                    repeat(count) {
                        require(offset + bytesPerPixel <= bytes.size) { "Truncated TGA file" }
                        if (index < pixels.size) pixels[index++] = readPixel(offset)
                        offset += bytesPerPixel
                    }
                }
            }
        }
        // Bit 5 set: rows are stored top to bottom; otherwise bottom to top.
        val topDown = descriptor and 0x20 != 0
        val rightToLeft = descriptor and 0x10 != 0
        if (topDown && !rightToLeft) return DecodedImage(width, height, pixels)
        val result = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            val sy = if (topDown) y else height - 1 - y
            val sx = if (rightToLeft) width - 1 - x else x
            result[y * width + x] = pixels[sy * width + sx]
        }
        return DecodedImage(width, height, result)
    }
}
