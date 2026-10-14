package dev.daniel.jigsaw.puzzle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class JigsawCutTest {
    private val cut = JigsawCut(rows = 4, cols = 6, random = Random(7))

    @Test
    fun `every outline is a closed clockwise loop`() {
        for (r in 0 until cut.rows) {
            for (c in 0 until cut.cols) {
                val edges = cut.outline(r, c)
                for (k in edges.indices) assertEquals(edges[k].end, edges[(k + 1) % edges.size].start)
                assertEquals(Pt(c.toFloat(), r.toFloat()), edges[0].start)
            }
        }
    }

    @Test
    fun `neighbouring pieces share the identical cut in opposite directions`() {
        for (r in 0 until cut.rows) {
            for (c in 0 until cut.cols) {
                if (c + 1 < cut.cols) assertEquals(cut.outline(r, c)[RIGHT], cut.outline(r, c + 1)[LEFT].reversed())
                if (r + 1 < cut.rows) assertEquals(cut.outline(r, c)[BOTTOM], cut.outline(r + 1, c)[TOP].reversed())
            }
        }
    }

    @Test
    fun `border edges are straight and interior edges are tabbed within padding`() {
        for (r in 0 until cut.rows) {
            for (c in 0 until cut.cols) {
                val edges = cut.outline(r, c)
                assertEquals(r == 0, edges[TOP].isStraight)
                assertEquals(c == cut.cols - 1, edges[RIGHT].isStraight)
                assertEquals(r == cut.rows - 1, edges[BOTTOM].isStraight)
                assertEquals(c == 0, edges[LEFT].isStraight)
                val points = edges.flatMap { it.points }
                assertTrue(points.all { it.x >= c - JigsawCut.PAD && it.x <= c + 1 + JigsawCut.PAD })
                assertTrue(points.all { it.y >= r - JigsawCut.PAD && it.y <= r + 1 + JigsawCut.PAD })
            }
        }
    }

    @Test
    fun `tabs go both ways across the puzzle`() {
        // Interior horizontal edges: tab bulges below (y > r) or above. Expect a mix.
        val directions = (1 until cut.rows).flatMap { r ->
            (0 until cut.cols).map { c -> cut.outline(r, c)[0].points[4].y > r }
        }
        assertTrue(directions.any { it } && directions.any { !it })
    }

    @Test
    fun `the same seed recreates the identical cut, so saved puzzles resume with the same shapes`() {
        val a = JigsawCut(rows = 4, cols = 6, random = Random(99L))
        val b = JigsawCut(rows = 4, cols = 6, random = Random(99L))
        val other = JigsawCut(rows = 4, cols = 6, random = Random(100L))
        assertEquals(outlines(a), outlines(b))
        assertFalse(outlines(a) == outlines(other))
    }

    private fun outlines(cut: JigsawCut) = (0 until cut.rows).flatMap { r ->
        (0 until cut.cols).map { c -> cut.outline(r, c) }
    }

    @Test
    fun `gridFor targets the piece count with near-square cells`() {
        for ((w, h) in listOf(1600 to 1200, 1200 to 1600, 1600 to 900, 1000 to 1000)) {
            val grid = JigsawCut.gridFor(w, h, PieceCount.P24)
            val cellAspect = (w.toFloat() / grid.cols) / (h.toFloat() / grid.rows)
            assertTrue(abs(grid.rows * grid.cols - 24) <= 6, "$w×$h → $grid")
            assertTrue(cellAspect in 0.7f..1.45f, "$w×$h cell aspect $cellAspect")
        }
        val grid = JigsawCut.gridFor(4000, 200, PieceCount.P24)
        assertFalse(grid.rows < 2 || grid.cols < 2)
    }

    private companion object {
        // Indices into JigsawCut.outline(), which runs clockwise from the top edge.
        const val TOP = 0
        const val RIGHT = 1
        const val BOTTOM = 2
        const val LEFT = 3
    }
}
