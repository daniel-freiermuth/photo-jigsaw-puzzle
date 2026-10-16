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
    val (cut, random) = BitmapPicture(picture).cutNew(settings, seed)
    val grid = cut.board.grid
    val layout = boardLayout(canvas, picture.width.toFloat(), picture.height.toFloat(), grid)
    cut.board.scatter(layout.scatter, random)
    return renderPile(cut, cut.board.snapshot, maxSide)
}

/** Just the pieces, framed tightly around them. */
private fun renderPile(cut: CutPuzzle, board: BoardSnapshot, maxSide: Int): Bitmap {
    val pad = JigsawCut.PAD
    val frame = Bounds(
        minX = board.xs.min() - pad,
        minY = board.ys.min() - pad,
        maxX = board.xs.max() + 1f + pad,
        maxY = board.ys.max() + 1f + pad,
    )
    return render(cut, board, frame, maxSide, drawBoard = false)
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
            val left = board.xs[i] * cut.cellWidth - cut.padX
            val top = board.ys[i] * cut.cellHeight - cut.padY
            val piece = cut.pieces[i]
            withRotation(
                board.turns.getOrElse(i) { 0 } * PuzzleBoard.QUARTER_TURN_DEGREES,
                (board.xs[i] + PuzzleBoard.PIECE_CENTER) * cut.cellWidth,
                (board.ys[i] + PuzzleBoard.PIECE_CENTER) * cut.cellHeight,
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
