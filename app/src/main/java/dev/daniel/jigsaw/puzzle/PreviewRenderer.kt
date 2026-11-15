package dev.daniel.jigsaw.puzzle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import kotlin.math.ceil
import kotlin.math.max

/**
 * Gallery image of a puzzle in progress, drawn by re-cutting [picture] (typically the thumbnail)
 * with the saved seed and laying the pieces out as saved. Once any piece sits on the board (its
 * centre inside the target area) only the board is shown; before that, just the loose pieces, turned
 * per [settings] since an unstarted puzzle follows the current rotation setting.
 */
fun renderProgress(picture: Bitmap, saved: SavedPuzzle.InProgress, settings: PuzzleSettings, maxSide: Int): Bitmap {
    val grid = saved.grid
    val cut = BitmapPicture(picture).cutSaved(saved, settings.rotatePieces).cut
    val board = cut.board.snapshot
    return if (saved.started) {
        render(cut, board, Bounds(0f, 0f, grid.cols.toFloat(), grid.rows.toFloat()), maxSide, drawBoard = true)
    } else {
        renderPile(cut, board, maxSide)
    }
}

/**
 * Gallery image of a puzzle that was never opened: what opening it and leaving straight away would
 * look like. [picture] is cut like a new puzzle per [settings], scattered over the tray exactly as
 * the puzzle screen would on [canvas], and framed on the pieces. [seed] keeps it stable between renders.
 */
fun renderUntouched(picture: Bitmap, canvas: CanvasSpec, settings: PuzzleSettings, seed: Long, maxSide: Int): Bitmap {
    val grid = JigsawCut.gridFor(picture.width, picture.height, settings.pieces)
    val (cut, random) = BitmapPicture(picture).cutNew(settings, seed, grid)
    val layout = boardLayout(canvas, picture.width.toFloat(), picture.height.toFloat(), grid)
    cut.board.scatter(layout.scatter, random)
    return renderPile(cut, cut.board.snapshot, maxSide)
}

/** Just the pieces, framed tightly around them. */
private fun renderPile(cut: CutPuzzle, board: BoardSnapshot, maxSide: Int): Bitmap =
    render(cut, board, pileFrame(board, cut.cellWidth, cut.cellHeight), maxSide, drawBoard = false)

/**
 * Grid-unit frame holding every piece with room for its tabs, as turned: a quarter-turned piece on
 * a non-square cell spans the other axis's cell size, so its half-extents swap.
 */
internal fun pileFrame(board: BoardSnapshot, cellWidth: Float, cellHeight: Float): Bounds {
    val half = 0.5f + JigsawCut.PAD
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (i in board.xs.indices) {
        val sideways = board.turns.getOrElse(i) { 0 } % 2 != 0
        val halfX = if (sideways) half * cellHeight / cellWidth else half
        val halfY = if (sideways) half * cellWidth / cellHeight else half
        val cx = board.xs[i] + 0.5f
        val cy = board.ys[i] + 0.5f
        minX = minOf(minX, cx - halfX)
        maxX = maxOf(maxX, cx + halfX)
        minY = minOf(minY, cy - halfY)
        maxY = maxOf(maxY, cy + halfY)
    }
    return Bounds(minX, minY, maxX, maxY)
}

/** Draws the pieces (and optionally the board) within [frame], in grid units, clipped to it. */
private fun render(cut: CutPuzzle, board: BoardSnapshot, frame: Bounds, maxSide: Int, drawBoard: Boolean): Bitmap {
    val frameW = (frame.maxX - frame.minX) * cut.cellWidth
    val frameH = (frame.maxY - frame.minY) * cut.cellHeight
    val scale = maxSide / max(frameW, frameH)
    val bitmap = createBitmap(max(1, ceil(frameW * scale).toInt()), max(1, ceil(frameH * scale).toInt()))
    Canvas(bitmap).apply {
        scale(scale, scale)
        translate(-frame.minX * cut.cellWidth, -frame.minY * cut.cellHeight)
        clipRect(
            frame.minX * cut.cellWidth,
            frame.minY * cut.cellHeight,
            frame.maxX * cut.cellWidth,
            frame.maxY * cut.cellHeight,
        )
        if (drawBoard) {
            val right = cut.board.cols * cut.cellWidth
            val bottom = cut.board.rows * cut.cellHeight
            drawRect(0f, 0f, right, bottom, Paint().apply { color = BOARD_FILL })
        }
        val piecePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        // Alpha-only outline masks are painted in the paint's colour.
        val outlinePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = PIECE_OUTLINE }
        for (i in board.order) {
            val left = cut.pieceLeft(board.xs[i])
            val top = cut.pieceTop(board.ys[i])
            val piece = cut.pieces[i]
            withRotation(
                board.turns.getOrElse(i) { 0 } * PuzzleBoard.QUARTER_TURN_DEGREES,
                cut.pieceCenterX(board.xs[i]),
                cut.pieceCenterY(board.ys[i]),
            ) {
                drawBitmap(piece.image.asAndroidBitmap(), left, top, piecePaint)
                drawBitmap(piece.outline.asAndroidBitmap(), left, top, outlinePaint)
            }
        }
    }
    return bitmap
}

// Theme-neutral greys: previews are rendered once and shown in light and dark mode alike.
private const val BOARD_FILL = 0x22808080
private const val PIECE_OUTLINE = 0x99606060.toInt()
