package dev.daniel.jigsaw.puzzle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BoardLayoutTest {
    private val margin = 12f

    @Test
    fun `portrait canvases put the tray wholly below the board and on screen`() {
        // A tall phone and a square canvas both take the portrait branch. The tall picture makes the
        // board's share of the height, not the canvas width, limit its size.
        val cases = listOf(
            Case(CanvasSpec(1080f, 1900f, margin), 4000f, 3000f, rows = 4, cols = 6),
            Case(CanvasSpec(1080f, 1900f, margin), 1000f, 3000f, rows = 6, cols = 2),
            Case(CanvasSpec(1000f, 1000f, margin), 3000f, 1000f, rows = 2, cols = 6),
        )
        for (case in cases) {
            val layout = case.layout()
            val scatter = layout.scatter
            assertTrue(scatter.fitsCell, "$case: tray $scatter can't hold a cell")
            assertTrue(scatter.minY >= case.rows, "$case: tray $scatter overlaps the board rows")
            assertInside(scatter, layout.screen, case)
        }
    }

    @Test
    fun `landscape canvases put the tray wholly right of the board and on screen`() {
        // The wide picture makes the board's share of the width, not the canvas height, limit its size.
        val cases = listOf(
            Case(CanvasSpec(1900f, 1080f, margin), 4000f, 3000f, rows = 4, cols = 6),
            Case(CanvasSpec(1900f, 1080f, margin), 3000f, 1000f, rows = 2, cols = 6),
        )
        for (case in cases) {
            val layout = case.layout()
            val scatter = layout.scatter
            assertTrue(scatter.fitsCell, "$case: tray $scatter can't hold a cell")
            assertTrue(scatter.minX >= case.cols, "$case: tray $scatter overlaps the board columns")
            assertInside(scatter, layout.screen, case)
        }
    }

    @Test
    fun `a tray too small for one cell falls back to the whole canvas inset by the tab padding`() {
        val grid = JigsawCut.gridFor(1600, 900, PieceCount.P12)
        val canvas = CanvasSpec(800f, 800f, margin)
        val layout = boardLayout(canvas, 1600f, 900f, grid.rows, grid.cols)
        // The space below the board, minus tab padding, is less than one row tall.
        val boardBottom = layout.origin.y + 900f * layout.scale
        val trayRows = (canvas.height - boardBottom - margin) / layout.cellSize.y - 2 * JigsawCut.PAD
        assertTrue(trayRows < 1f, "expected the tray to be too short, got $trayRows rows")

        val s = layout.screen
        val pad = JigsawCut.PAD
        assertEquals(Bounds(s.minX + pad, s.minY + pad, s.maxX - pad, s.maxY - pad), layout.scatter)
        assertTrue(layout.scatter.fitsCell)
    }

    @Test
    fun `grid-unit areas depend on the picture's aspect ratio, not its resolution`() {
        for (canvas in listOf(CanvasSpec(1080f, 1900f, margin), CanvasSpec(1900f, 1080f, margin))) {
            val large = boardLayout(canvas, 1600f, 1200f, 4, 6)
            val small = boardLayout(canvas, 400f, 300f, 4, 6)
            assertClose(large.screen, small.screen, "screen on $canvas")
            assertClose(large.scatter, small.scatter, "scatter on $canvas")
            assertEquals(large.origin.x, small.origin.x, 1e-3f)
            assertEquals(large.origin.y, small.origin.y, 1e-3f)
        }
    }

    @Test
    fun `the board sits inside the canvas margins and the screen covers it`() {
        val cases = listOf(
            Case(CanvasSpec(1080f, 1900f, margin), 4000f, 3000f, rows = 4, cols = 6),
            Case(CanvasSpec(1080f, 1900f, margin), 1000f, 3000f, rows = 6, cols = 2),
            Case(CanvasSpec(1900f, 1080f, margin), 4000f, 3000f, rows = 4, cols = 6),
            Case(CanvasSpec(1900f, 1080f, margin), 3000f, 1000f, rows = 2, cols = 6),
            Case(CanvasSpec(800f, 800f, margin), 1600f, 900f, rows = 2, cols = 5),
        )
        val eps = 1e-3f
        for (case in cases) {
            val layout = case.layout()
            val (w, h) = case.canvas.width to case.canvas.height
            val o = layout.origin
            assertTrue(o.x >= margin - eps && o.y >= margin - eps, "$case: origin $o is inside the margin")
            assertTrue(o.x + case.pictureWidth * layout.scale <= w - margin + eps, "$case: board overflows right")
            assertTrue(o.y + case.pictureHeight * layout.scale <= h - margin + eps, "$case: board overflows bottom")
            assertEquals(case.pictureWidth / case.cols * layout.scale, layout.cellSize.x, eps)
            assertEquals(case.pictureHeight / case.rows * layout.scale, layout.cellSize.y, eps)

            val s = layout.screen
            assertTrue(s.minX <= 0f && s.minY <= 0f, "$case: screen $s misses the board's top-left")
            assertTrue(s.maxX >= case.cols && s.maxY >= case.rows, "$case: screen $s misses the board's bottom-right")
        }
    }

    private data class Case(
        val canvas: CanvasSpec,
        val pictureWidth: Float,
        val pictureHeight: Float,
        val rows: Int,
        val cols: Int,
    ) {
        fun layout() = boardLayout(canvas, pictureWidth, pictureHeight, rows, cols)
    }

    private fun assertInside(inner: Bounds, outer: Bounds, case: Case) {
        val inside = inner.minX >= outer.minX && inner.minY >= outer.minY &&
            inner.maxX <= outer.maxX && inner.maxY <= outer.maxY
        assertTrue(inside, "$case: $inner is not inside the screen $outer")
    }

    private fun assertClose(expected: Bounds, actual: Bounds, what: String) {
        val eps = 1e-4f
        assertEquals(expected.minX, actual.minX, eps, what)
        assertEquals(expected.minY, actual.minY, eps, what)
        assertEquals(expected.maxX, actual.maxX, eps, what)
        assertEquals(expected.maxY, actual.maxY, eps, what)
    }
}
