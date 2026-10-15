package dev.daniel.jigsaw.puzzle

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Point in grid units: x in columns, y in rows. Piece (r, c) occupies [c, c+1] × [r, r+1]. */
data class Pt(val x: Float, val y: Float)

/**
 * One piece edge. Two points = straight line; ten points = three chained cubic Béziers
 * (p0 C p1 p2 p3, C p4 p5 p6, C p7 p8 p9) forming a tab or blank.
 */
data class Edge(val points: List<Pt>) {
    val start get() = points.first()
    val end get() = points.last()
    fun reversed() = Edge(points.asReversed().toList())
    val isStraight get() = points.size == 2
}

/** Size of a puzzle's piece grid. */
data class Grid(val rows: Int, val cols: Int)

/**
 * Generates classic tab/blank cuts for a [grid]. Shared edges are generated once and
 * traversed in opposite directions by the two neighbouring pieces, so they always interlock.
 */
class JigsawCut(val grid: Grid, random: Random) {
    val rows get() = grid.rows
    val cols get() = grid.cols

    /** horizontal[r][c]: edge along y = r from x = c to c+1, for r in 1 until rows. */
    private val horizontal = Array(rows + 1) { r ->
        Array(cols) { c ->
            if (r == 0 || r == rows) {
                straight(Pt(c.toFloat(), r.toFloat()), Pt(c + 1f, r.toFloat()))
            } else {
                tab(random) { u, v -> Pt(c + u, r + v) }
            }
        }
    }

    /** vertical[r][c]: edge along x = c from y = r to r+1, for c in 1 until cols. */
    private val vertical = Array(rows) { r ->
        Array(cols + 1) { c ->
            if (c == 0 || c == cols) {
                straight(Pt(c.toFloat(), r.toFloat()), Pt(c.toFloat(), r + 1f))
            } else {
                tab(random) { u, v -> Pt(c + v, r + u) }
            }
        }
    }

    /** Clockwise outline (top, right, bottom, left) of piece (row, col) in grid units. */
    fun outline(row: Int, col: Int): List<Edge> = listOf(
        horizontal[row][col],
        vertical[row][col + 1],
        horizontal[row + 1][col].reversed(),
        vertical[row][col].reversed(),
    )

    private fun straight(a: Pt, b: Pt) = Edge(listOf(a, b))

    /**
     * Classic jigsaw tab after Draradech's generator. `u` runs along the edge, `v` across it;
     * a random flip decides which neighbour gets the tab and which the blank.
     */
    // The literals are the published control-point layout of the tab curve; naming each would hide its shape.
    @Suppress("MagicNumber")
    private fun tab(random: Random, toGrid: (Float, Float) -> Pt): Edge {
        fun jitter() = (random.nextFloat() * 2f - 1f) * JITTER
        val flip = if (random.nextBoolean()) 1f else -1f
        val t = TAB_SIZE
        val a = jitter()
        val b = jitter()
        val c = jitter()
        val d = jitter()
        val e = jitter()
        val uv = listOf(
            0f to 0f,
            0.2f to a,
            0.5f + b + d to -t + c,
            0.5f - t + b to t + c,
            0.5f - 2 * t + b - d to 3 * t + c,
            0.5f + 2 * t + b - d to 3 * t + c,
            0.5f + t + b to t + c,
            0.5f + b + d to -t + c,
            0.8f to e,
            1f to 0f,
        )
        return Edge(uv.map { (u, v) -> toGrid(u, v * flip) })
    }

    companion object {
        const val TAB_SIZE = 0.09f
        const val JITTER = 0.035f

        /** How far (in cell units) a tab can stick out past its cell: 3·t + jitter, plus slack. */
        const val PAD = 0.35f

        /** Picks rows × cols ≈ [pieces] pieces with cells as close to square as possible. */
        fun gridFor(imageWidth: Int, imageHeight: Int, pieces: PieceCount): Grid {
            val aspect = imageWidth.toFloat() / imageHeight
            val cols = max(2, sqrt(pieces.target * aspect).roundToInt())
            val rows = max(2, (pieces.target / cols.toFloat()).roundToInt())
            return Grid(rows = rows, cols = cols)
        }
    }
}
