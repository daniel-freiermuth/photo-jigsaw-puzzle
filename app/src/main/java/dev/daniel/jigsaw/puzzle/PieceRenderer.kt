package dev.daniel.jigsaw.puzzle

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Region
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import kotlin.math.ceil

/**
 * A pre-cut piece, all in source-bitmap pixels: its masked image, an alpha-only outline mask
 * (tinted at draw time so it can follow the theme), and a hit-test region.
 */
class PieceImage(val image: ImageBitmap, val outline: ImageBitmap, val region: Region)

/** Everything needed to draw a puzzle; positions live in [board]. */
class CutPuzzle(
    val source: ImageBitmap,
    val board: PuzzleBoard,
    val pieces: List<PieceImage>,
    /** Cell size in source-bitmap pixels. */
    val cellWidth: Float,
    val cellHeight: Float,
) {
    val padX get() = JigsawCut.PAD * cellWidth
    val padY get() = JigsawCut.PAD * cellHeight
}

/** A decoded picture to cut puzzles from, [width] × [height] pixels. */
interface PuzzlePicture {
    val width: Int
    val height: Int

    /** The whole picture, as shown once its puzzle is solved. */
    fun asImageBitmap(): ImageBitmap

    /** Cuts the picture following [cut], with pieces placed per [board]; see [cutPuzzle]. */
    fun cut(cut: JigsawCut, board: PuzzleBoard): CutPuzzle
}

/** A [PuzzlePicture] backed by [bitmap]. */
class BitmapPicture(private val bitmap: Bitmap) : PuzzlePicture {
    override val width get() = bitmap.width
    override val height get() = bitmap.height

    override fun asImageBitmap() = bitmap.asImageBitmap()

    override fun cut(cut: JigsawCut, board: PuzzleBoard) = cutPuzzle(bitmap, cut, board)
}

/**
 * Cell width / cell height of a [width] × [height] picture cut into [rows] × [cols]: the aspect a
 * [PuzzleBoard] needs to turn pieces as the picture would.
 */
internal fun cellAspect(width: Int, height: Int, rows: Int, cols: Int): Float =
    (width.toFloat() / cols) / (height.toFloat() / rows)

/**
 * Cuts [bitmap] into rows × cols pieces following [cut], with pieces placed per [board] (a fresh,
 * solved-layout board by default). CPU-heavy: run off the main thread.
 */
fun cutPuzzle(bitmap: Bitmap, cut: JigsawCut, board: PuzzleBoard = PuzzleBoard(cut.rows, cut.cols)): CutPuzzle {
    require(board.rows == cut.rows && board.cols == cut.cols) { "Board does not match the cut" }
    val rows = cut.rows
    val cols = cut.cols
    val cellW = bitmap.width.toFloat() / cols
    val cellH = bitmap.height.toFloat() / rows
    val pad = JigsawCut.PAD
    val pieceW = ceil(cellW * (1 + 2 * pad)).toInt()
    val pieceH = ceil(cellH * (1 + 2 * pad)).toInt()

    val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, minOf(cellW, cellH) * OUTLINE_WIDTH)
    }
    val clip = Region(0, 0, pieceW, pieceH)
    val matrix = Matrix()

    val pieces = (0 until rows * cols).map { i ->
        val row = i / cols
        val col = i % cols
        val path = piecePath(cut.outline(row, col)) { p ->
            ((p.x - col + pad) * cellW) to ((p.y - row + pad) * cellH)
        }
        val piece = createBitmap(pieceW, pieceH)
        matrix.setTranslate(-(col - pad) * cellW, -(row - pad) * cellH)
        shader.setLocalMatrix(matrix)
        Canvas(piece).drawPath(path, fill)
        val outline = createBitmap(pieceW, pieceH, Bitmap.Config.ALPHA_8)
        Canvas(outline).drawPath(path, stroke)
        PieceImage(piece.asImageBitmap(), outline.asImageBitmap(), Region().apply { setPath(path, clip) })
    }
    return CutPuzzle(bitmap.asImageBitmap(), board, pieces, cellW, cellH)
}

private inline fun piecePath(edges: List<Edge>, toPx: (Pt) -> Pair<Float, Float>): Path {
    val path = Path()
    val (sx, sy) = toPx(edges.first().start)
    path.moveTo(sx, sy)
    for (edge in edges) {
        val pts = edge.points.map(toPx)
        if (edge.isStraight) {
            path.lineTo(pts[1].first, pts[1].second)
        } else {
            // Each cubic segment consumes two control points and an end point.
            for (k in 1 until pts.size step POINTS_PER_CUBIC) {
                path.cubicTo(
                    pts[k].first,
                    pts[k].second,
                    pts[k + 1].first,
                    pts[k + 1].second,
                    pts[k + 2].first,
                    pts[k + 2].second,
                )
            }
        }
    }
    path.close()
    return path
}

/** Outline stroke width as a fraction of the smaller cell side. */
private const val OUTLINE_WIDTH = 0.012f

private const val POINTS_PER_CUBIC = 3
