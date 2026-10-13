package dev.daniel.jigsaw.puzzle

/** Axis-aligned rectangle in grid units (x in columns, y in rows). */
data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    /** Whether a whole 1×1 cell fits inside. */
    val fitsCell get() = maxX - minX >= 1f && maxY - minY >= 1f
}
