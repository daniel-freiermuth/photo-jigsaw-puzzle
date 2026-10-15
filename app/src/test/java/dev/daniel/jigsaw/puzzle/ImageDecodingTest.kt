package dev.daniel.jigsaw.puzzle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * decodeSampled itself needs a real BitmapFactory/Matrix, which JVM unit tests don't have; these pin the
 * transform it hands to Matrix, with the EXIF block parsed by the real ExifInterface.
 */
class ImageDecodingTest {
    @Test
    fun `every EXIF orientation displays the image upright`() {
        // Where the stored image's top-left and top-right corners end up, per the EXIF 2.3 spec
        // (row 0 / column 0 positions), and whether width and height swap.
        val expected = mapOf(
            1 to Display(Corner.TOP_LEFT, Corner.TOP_RIGHT, swapsSides = false),
            2 to Display(Corner.TOP_RIGHT, Corner.TOP_LEFT, swapsSides = false),
            3 to Display(Corner.BOTTOM_RIGHT, Corner.BOTTOM_LEFT, swapsSides = false),
            4 to Display(Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT, swapsSides = false),
            5 to Display(Corner.TOP_LEFT, Corner.BOTTOM_LEFT, swapsSides = true),
            6 to Display(Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT, swapsSides = true),
            7 to Display(Corner.BOTTOM_RIGHT, Corner.TOP_RIGHT, swapsSides = true),
            8 to Display(Corner.BOTTOM_LEFT, Corner.TOP_LEFT, swapsSides = true),
        )
        for ((orientation, display) in expected) {
            val values = orientationMatrixValues(readExif(jpegWithOrientation(orientation)), scale = 1f)
            val placed = place(values, WIDTH, HEIGHT)
            assertEquals(display.topLeft, placed.topLeft, "orientation $orientation: stored top-left corner")
            assertEquals(display.topRight, placed.topRight, "orientation $orientation: stored top-right corner")
            val size = if (display.swapsSides) HEIGHT to WIDTH else WIDTH to HEIGHT
            assertEquals(size, placed.size, "orientation $orientation: displayed size")
        }
    }

    @Test
    fun `bytes without an EXIF block are left as stored`() {
        val withoutExif = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        for (bytes in listOf(withoutExif, "not an image".toByteArray())) {
            val placed = place(orientationMatrixValues(readExif(bytes), scale = 1f), WIDTH, HEIGHT)
            assertEquals(Placed(Corner.TOP_LEFT, Corner.TOP_RIGHT, WIDTH to HEIGHT), placed)
        }
    }

    @Test
    fun `downscaling applies before the orientation`() {
        val values = orientationMatrixValues(readExif(jpegWithOrientation(TRANSPOSE)), scale = 0.5f)
        assertEquals(
            Placed(Corner.TOP_LEFT, Corner.BOTTOM_LEFT, HEIGHT / 2 to WIDTH / 2),
            place(values, WIDTH, HEIGHT),
        )
    }

    private enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private data class Display(val topLeft: Corner, val topRight: Corner, val swapsSides: Boolean)

    private data class Placed(val topLeft: Corner, val topRight: Corner, val size: Pair<Int, Int>)

    /** Maps a [width]×[height] image through [values] the way Bitmap.createBitmap does: shifted to start at 0. */
    private fun place(values: FloatArray, width: Int, height: Int): Placed {
        fun map(x: Int, y: Int) = (values[0] * x + values[1] * y) to (values[3] * x + values[4] * y)
        val corners = listOf(map(0, 0), map(width, 0), map(0, height), map(width, height))
        val minX = corners.minOf { it.first }
        val maxX = corners.maxOf { it.first }
        val minY = corners.minOf { it.second }
        val maxY = corners.maxOf { it.second }
        fun corner(p: Pair<Float, Float>) = when {
            p.first == minX && p.second == minY -> Corner.TOP_LEFT
            p.first == maxX && p.second == minY -> Corner.TOP_RIGHT
            p.first == minX -> Corner.BOTTOM_LEFT
            else -> Corner.BOTTOM_RIGHT
        }
        return Placed(corner(corners[0]), corner(corners[1]), (maxX - minX).toInt() to (maxY - minY).toInt())
    }

    /** A JPEG carrying only an APP1 EXIF block whose single IFD0 entry is Orientation = [orientation]. */
    private fun jpegWithOrientation(orientation: Int): ByteArray {
        val tiff = listOf(
            'M'.code, 'M'.code, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // big-endian header, IFD0 at offset 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation, 0x00, 0x00, // Orientation, SHORT
            0x00, 0x00, 0x00, 0x00, // no next IFD
        )
        val app1 = "Exif".map { it.code } + listOf(0x00, 0x00) + tiff
        val length = app1.size + 2
        val bytes = listOf(0xFF, 0xD8, 0xFF, 0xE1, length shr 8, length and 0xFF) + app1 + listOf(0xFF, 0xD9)
        return ByteArray(bytes.size) { bytes[it].toByte() }
    }

    private companion object {
        const val WIDTH = 4
        const val HEIGHT = 2
        const val TRANSPOSE = 5
    }
}
