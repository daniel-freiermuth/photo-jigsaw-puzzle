package dev.daniel.jigsaw.puzzle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

/**
 * decodeSampled itself needs a real BitmapFactory/Matrix, which JVM unit tests don't have; these pin the
 * sample size and scale it picks and the transform it hands to Matrix, with the EXIF block parsed by the
 * real ExifInterface.
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

    @Test
    fun `sample size is the largest power of two that keeps at least maxSide pixels`() {
        val expected = mapOf(
            1 to 1,
            PUZZLE_SIDE - 1 to 1,
            PUZZLE_SIDE to 1,
            2 * PUZZLE_SIDE - 1 to 1,
            2 * PUZZLE_SIDE to 2,
            4 * PUZZLE_SIDE - 1 to 2,
            4 * PUZZLE_SIDE to 4,
            8 * PUZZLE_SIDE + 1 to 8,
        )
        for ((longestSide, sample) in expected) {
            assertEquals(sample, sampleSizeFor(longestSide, PUZZLE_SIDE), "longest side $longestSide")
        }
    }

    @Test
    fun `downscale only shrinks bitmaps longer than maxSide`() {
        assertEquals(1f, downscaleFor(PUZZLE_SIDE - 1, PUZZLE_SIDE))
        assertEquals(1f, downscaleFor(PUZZLE_SIDE, PUZZLE_SIDE))
        assertEquals(PUZZLE_SIDE / (2f * PUZZLE_SIDE - 1), downscaleFor(2 * PUZZLE_SIDE - 1, PUZZLE_SIDE))
    }

    @Test
    fun `sampling then downscaling lands the longest side on maxSide`() {
        // BitmapFactory rounds a subsampled side down (most codecs) or up (JPEG), and Bitmap.createBitmap
        // rounds the transformed size to the nearest pixel; the contract has to hold under either rounding.
        val roundings = listOf<(Int, Int) -> Int>({ side, sample -> side / sample }, ::ceilDiv)
        for (maxSide in listOf(THUMBNAIL_SIDE, PUZZLE_SIDE)) {
            for (longestSide in 1..LARGEST_PHOTO_SIDE) {
                val sample = sampleSizeFor(longestSide, maxSide)
                assertTrue(longestSide / (sample * 2) < maxSide, "$longestSide→$maxSide: sample $sample too small")
                for (rounding in roundings) {
                    val decoded = rounding(longestSide, sample)
                    val displayed = (decoded * downscaleFor(decoded, maxSide)).roundToInt()
                    assertEquals(minOf(longestSide, maxSide), displayed, "$longestSide→$maxSide via sample $sample")
                }
            }
        }
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

    private fun ceilDiv(side: Int, sample: Int) = (side + sample - 1) / sample

    private companion object {
        const val WIDTH = 4
        const val HEIGHT = 2
        const val TRANSPOSE = 5

        // The maxSide values the app passes: GalleryThumbnail.MAX_SIDE and AppViewModel.MAX_PUZZLE_SIDE.
        const val THUMBNAIL_SIDE = 400
        const val PUZZLE_SIDE = 1600

        /** Longer side of a 200 MP phone photo. */
        const val LARGEST_PHOTO_SIDE = 16_320
    }
}
