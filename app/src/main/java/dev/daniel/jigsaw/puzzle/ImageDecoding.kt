package dev.daniel.jigsaw.puzzle

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.IOException

/** Decodes [bytes] so the longest side is at most about [maxSide], honouring EXIF orientation. */
fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not a decodable image")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: throw IOException("Not a decodable image")

    val longest = maxOf(decoded.width, decoded.height)
    val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
    val exif = readExif(bytes)
    val matrix = Matrix().apply {
        postScale(scale, scale)
        // ExifInterface describes each orientation as an optional horizontal mirror followed by a rotation.
        if (exif?.isFlipped == true) postScale(-1f, 1f)
        exif?.let { postRotate(it.rotationDegrees.toFloat()) }
    }
    if (matrix.isIdentity) return decoded
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        .also { if (it !== decoded) decoded.recycle() }
}

/** Null when the bytes carry no readable EXIF block. */
private fun readExif(bytes: ByteArray): ExifInterface? = try {
    ExifInterface(ByteArrayInputStream(bytes))
} catch (_: IOException) {
    null
}
