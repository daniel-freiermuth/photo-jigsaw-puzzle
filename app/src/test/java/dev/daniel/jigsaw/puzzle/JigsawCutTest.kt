package dev.daniel.jigsaw.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class JigsawCutTest {
    private val cut = JigsawCut(rows = 4, cols = 6, random = Random(7))

    @Test
    fun `every outline is a closed clockwise loop`() {
        for (r in 0 until cut.rows) for (c in 0 until cut.cols) {
            val edges = cut.outline(r, c)
            for (k in edges.indices) assertEquals(edges[k].end, edges[(k + 1) % edges.size].start)
            assertEquals(Pt(c.toFloat(), r.toFloat()), edges[0].start)
        }
    }

    @Test
    fun `neighbouring pieces share the identical cut in opposite directions`() {
        for (r in 0 until cut.rows) for (c in 0 until cut.cols) {
            if (c + 1 < cut.cols) assertEquals(cut.outline(r, c)[1], cut.outline(r, c + 1)[3].reversed())
            if (r + 1 < cut.rows) assertEquals(cut.outline(r, c)[2], cut.outline(r + 1, c)[0].reversed())
        }
    }

    @Test
    fun `border edges are straight and interior edges are tabbed within padding`() {
        for (r in 0 until cut.rows) for (c in 0 until cut.cols) {
            val (top, right, bottom, left) = cut.outline(r, c)
            assertEquals(r == 0, top.isStraight)
            assertEquals(c == cut.cols - 1, right.isStraight)
            assertEquals(r == cut.rows - 1, bottom.isStraight)
            assertEquals(c == 0, left.isStraight)
            for (edge in listOf(top, right, bottom, left)) for (p in edge.points) {
                assertTrue(p.x >= c - JigsawCut.PAD && p.x <= c + 1 + JigsawCut.PAD)
                assertTrue(p.y >= r - JigsawCut.PAD && p.y <= r + 1 + JigsawCut.PAD)
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
    fun `gridFor targets the piece count with near-square cells`() {
        for ((w, h) in listOf(1600 to 1200, 1200 to 1600, 1600 to 900, 1000 to 1000)) {
            val (rows, cols) = JigsawCut.gridFor(w, h, 24)
            val cellAspect = (w.toFloat() / cols) / (h.toFloat() / rows)
            assertTrue("$w×$h → $rows×$cols", abs(rows * cols - 24) <= 6)
            assertTrue("$w×$h cell aspect $cellAspect", cellAspect in 0.7f..1.45f)
        }
        val (rows, cols) = JigsawCut.gridFor(4000, 200, 24)
        assertFalse(rows < 2 || cols < 2)
    }
}
