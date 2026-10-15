package dev.daniel.jigsaw.puzzle

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Decodes [bytes] so the longest side is at most about [maxSide], honouring EXIF orientation. */
fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UndecodableImageException()
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: throw UndecodableImageException()

    val longest = maxOf(decoded.width, decoded.height)
    val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
    val matrix = Matrix().apply { setValues(orientationMatrixValues(readExif(bytes), scale)) }
    if (matrix.isIdentity) return decoded
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        .also { if (it !== decoded) decoded.recycle() }
}

/**
 * [Matrix.setValues] input that scales by [scale] and then applies [exif]'s orientation. ExifInterface
 * describes each orientation as an optional horizontal mirror followed by a clockwise rotation; the mirror
 * must come first, or TRANSPOSE (5) and TRANSVERSE (7) come out upside down.
 */
internal fun orientationMatrixValues(exif: ExifInterface?, scale: Float): FloatArray {
    val mirror = if (exif?.isFlipped == true) -1f else 1f
    // Clockwise in image coordinates (y points down), like Matrix.setRotate: 90° maps (x, y) to (-y, x).
    // EXIF rotations are quarter turns, so rounding makes the sines and cosines exact.
    val radians = Math.toRadians(exif?.rotationDegrees?.toDouble() ?: 0.0)
    val cos = cos(radians).roundToInt().toFloat()
    val sin = sin(radians).roundToInt().toFloat()
    // rotate · mirror · scale = [cos -sin; sin cos] · [mirror 0; 0 1] · scale
    return floatArrayOf(
        cos * mirror * scale, -sin * scale, 0f,
        sin * mirror * scale, cos * scale, 0f,
        0f, 0f, 1f,
    )
}

/** Null when the bytes carry no readable EXIF block. */
internal fun readExif(bytes: ByteArray): ExifInterface? = try {
    ExifInterface(ByteArrayInputStream(bytes))
} catch (_: IOException) {
    null
}

/** The downloaded bytes are not an image format Android can decode. */
class UndecodableImageException : IOException("Not a decodable image")
