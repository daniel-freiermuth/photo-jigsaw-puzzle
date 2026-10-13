package dev.daniel.jigsaw.puzzle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.createBitmap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

/**
 * Gallery image of a puzzle in progress: once any piece sits on the board (its centre inside the
 * target area), just the board with what has been placed; before that, the pile of loose pieces.
 * Safe off the main thread: only reads the immutable piece bitmaps.
 */
fun renderPreview(cut: CutPuzzle, board: BoardSnapshot, maxSide: Int): Bitmap {
    val rows = cut.board.rows
    val cols = cut.board.cols
    val anyOnBoard = board.xs.indices.any { i ->
        val cx = board.xs[i] + CENTER
        val cy = board.ys[i] + CENTER
        cx in 0f..cols.toFloat() && cy in 0f..rows.toFloat()
    }
    return if (anyOnBoard) {
        render(cut, board, Bounds(0f, 0f, cols.toFloat(), rows.toFloat()), maxSide, drawBoard = true)
    } else {
        renderPile(cut, board, maxSide)
    }
}

/**
 * Gallery image of a puzzle that was never opened: [picture] (typically the thumbnail) cut into
 * about [pieces] pieces and dropped in a heap. [seed] keeps the heap stable between renders.
 */
fun renderUntouchedPile(picture: Bitmap, pieces: Int, seed: Long, maxSide: Int): Bitmap {
    val (rows, cols) = JigsawCut.gridFor(picture.width, picture.height, pieces)
    val random = Random(seed)
    val cut = cutPuzzle(picture, JigsawCut(rows, cols, random))
    cut.board.scatter(Bounds(0f, 0f, cols * PILE_SPREAD, rows * PILE_SPREAD), random)
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
            drawBitmap(piece.image.asAndroidBitmap(), left, top, piecePaint)
            drawBitmap(piece.outline.asAndroidBitmap(), left, top, outlinePaint)
        }
    }
    return bitmap
}

/** Offset from a piece's cell corner to its centre, in cell units. */
private const val CENTER = 0.5f

/** Heap area relative to the solved picture: below 1 so pieces overlap like a real pile. */
private const val PILE_SPREAD = 0.8f

// Theme-neutral greys: previews are rendered once and shown in light and dark mode alike.
private const val BOARD_FILL = 0x22808080
private const val PIECE_OUTLINE = 0x99606060.toInt()
