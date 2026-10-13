package dev.daniel.jigsaw.puzzle

import kotlin.math.min

/** Pixel size of the puzzle canvas and the margin kept around the board. */
data class CanvasSpec(val width: Float, val height: Float, val margin: Float)

/** Where the board sits on a canvas, plus the grid-unit areas pieces may occupy. */
data class BoardLayout(
    /** Canvas position of the board's top-left corner, in pixels. */
    val origin: Pt,
    /** Canvas pixels per picture pixel. */
    val scale: Float,
    /** On-canvas size of one grid cell, in pixels. */
    val cellSize: Pt,
    /** The whole canvas; groups must keep a grabbable part inside it. */
    val screen: Bounds,
    /** Where pieces start out, kept clear of the canvas edges so tabs aren't clipped. */
    val scatter: Bounds,
)

/** Fraction of the long canvas axis given to the board; the rest is the tray for loose pieces. */
private const val BOARD_SHARE = 0.55f

/**
 * Portrait: board on top, loose pieces below. Landscape: board left, loose pieces right.
 * Grid-unit results only depend on the picture's aspect ratio, not its resolution.
 */
fun boardLayout(canvas: CanvasSpec, pictureWidth: Float, pictureHeight: Float, rows: Int, cols: Int): BoardLayout {
    val w = canvas.width
    val h = canvas.height
    val margin = canvas.margin
    val portrait = h >= w
    val scale = if (portrait) {
        min((w - 2 * margin) / pictureWidth, (h * BOARD_SHARE - margin) / pictureHeight)
    } else {
        min((w * BOARD_SHARE - margin) / pictureWidth, (h - 2 * margin) / pictureHeight)
    }
    val origin = if (portrait) {
        Pt((w - pictureWidth * scale) / 2, margin)
    } else {
        Pt(margin, (h - pictureHeight * scale) / 2)
    }
    val cell = Pt(pictureWidth / cols * scale, pictureHeight / rows * scale)
    fun toGrid(left: Float, top: Float, right: Float, bottom: Float, inset: Float) = Bounds(
        minX = (left - origin.x) / cell.x + inset,
        minY = (top - origin.y) / cell.y + inset,
        maxX = (right - origin.x) / cell.x - inset,
        maxY = (bottom - origin.y) / cell.y - inset,
    )
    val pad = JigsawCut.PAD
    val tray = if (portrait) {
        toGrid(0f, origin.y + pictureHeight * scale + margin, w, h, pad)
    } else {
        toGrid(origin.x + pictureWidth * scale + margin, 0f, w, h, pad)
    }
    return BoardLayout(
        origin = origin,
        scale = scale,
        cellSize = cell,
        screen = toGrid(0f, 0f, w, h, inset = 0f),
        scatter = if (tray.fitsCell) tray else toGrid(0f, 0f, w, h, pad),
    )
}
