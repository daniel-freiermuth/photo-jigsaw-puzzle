package dev.daniel.jigsaw.puzzle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.createBitmap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the puzzle as it currently lies — board outline plus every piece at its [board] position —
 * into a bitmap whose longer side is [maxSide]. The frame spans the board and all pieces, so loose
 * pieces in the tray are included. Safe off the main thread: only reads the immutable piece bitmaps.
 */
fun renderPreview(cut: CutPuzzle, board: BoardSnapshot, maxSide: Int): Bitmap {
    val rows = cut.board.rows
    val cols = cut.board.cols
    val pad = JigsawCut.PAD
    // Frame in grid units: the board plus every piece including its tabs.
    val minX = min(0f, board.xs.min()) - pad
    val minY = min(0f, board.ys.min()) - pad
    val maxX = max(cols.toFloat(), board.xs.max() + 1f) + pad
    val maxY = max(rows.toFloat(), board.ys.max() + 1f) + pad
    val frameW = (maxX - minX) * cut.cellWidth
    val frameH = (maxY - minY) * cut.cellHeight
    val scale = maxSide / max(frameW, frameH)
    val bitmap = createBitmap(max(1, ceil(frameW * scale).toInt()), max(1, ceil(frameH * scale).toInt()))

    Canvas(bitmap).apply {
        scale(scale, scale)
        translate(-minX * cut.cellWidth, -minY * cut.cellHeight)
        val boardRight = cols * cut.cellWidth
        val boardBottom = rows * cut.cellHeight
        drawRect(0f, 0f, boardRight, boardBottom, Paint().apply { color = BOARD_FILL })
        drawRect(
            0f,
            0f,
            boardRight,
            boardBottom,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f / scale
                color = BOARD_STROKE
            },
        )
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

// Theme-neutral greys: previews are rendered once and shown in light and dark mode alike.
private const val BOARD_FILL = 0x22808080
private const val BOARD_STROKE = 0x99808080.toInt()
private const val PIECE_OUTLINE = 0x99606060.toInt()
