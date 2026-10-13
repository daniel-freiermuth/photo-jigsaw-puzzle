package dev.daniel.jigsaw.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PuzzleBoardTest {

    /** 2×2 board with every piece scattered far from the board and from each other. */
    private fun scatteredBoard() = PuzzleBoard(2, 2).apply {
        moveTo(this, 0, 10f, 10f)
        moveTo(this, 1, 20f, 10f)
        moveTo(this, 2, 10f, 20f)
        moveTo(this, 3, 20f, 20f)
    }

    private fun moveTo(board: PuzzleBoard, i: Int, x: Float, y: Float) =
        board.moveGroup(i, x - board.x(i), y - board.y(i))

    @Test
    fun `dropping next to the matching neighbour joins and aligns exactly`() {
        val board = scatteredBoard()
        moveTo(board, 1, 11.1f, 9.9f) // right neighbour of piece 0, slightly off
        assertTrue(board.drop(1, snapToBoard = false))
        assertTrue(board.sameGroup(0, 1))
        assertEquals(board.x(0) + 1f, board.x(1), 1e-5f)
        assertEquals(board.y(0), board.y(1), 1e-5f)
        assertFalse(board.isLocked(0))
    }

    @Test
    fun `pieces beyond tolerance or in the wrong slot stay separate`() {
        val board = scatteredBoard()
        moveTo(board, 1, 11.5f, 10f)
        assertFalse(board.drop(1, snapToBoard = false))
        moveTo(board, 1, 9f, 10f) // left of piece 0, but piece 1 belongs on its right
        assertFalse(board.drop(1, snapToBoard = false))
        assertFalse(board.sameGroup(0, 1))
    }

    @Test
    fun `joined group moves as one`() {
        val board = scatteredBoard()
        moveTo(board, 2, 10f, 11f)
        board.drop(2, snapToBoard = false)
        board.moveGroup(0, 3f, -2f)
        assertEquals(13f, board.x(2), 1e-5f)
        assertEquals(9f, board.y(2), 1e-5f)
    }

    @Test
    fun `without board snapping a piece on its solved spot stays loose`() {
        val board = scatteredBoard()
        moveTo(board, 0, 0.1f, -0.1f)
        assertFalse(board.drop(0, snapToBoard = false))
        assertFalse(board.isLocked(0))
        assertEquals(0.1f, board.x(0), 1e-5f)
        assertEquals(0, board.pieceAt { it == 0 })
    }

    @Test
    fun `with board snapping a group near its solved spot locks onto the board`() {
        val board = scatteredBoard()
        moveTo(board, 1, 11f, 10f)
        board.drop(1, snapToBoard = true)
        moveTo(board, 0, 0.1f, -0.1f) // drags piece 1 along
        assertTrue(board.drop(0, snapToBoard = true))
        assertTrue(board.isLocked(0) && board.isLocked(1))
        assertEquals(1f, board.x(1), 1e-5f)
        assertEquals(0f, board.y(1), 1e-5f)
        // Locked pieces are not draggable and are drawn beneath loose ones.
        assertNull(board.pieceAt { it == 0 })
        assertEquals(setOf(0, 1), board.drawOrder.take(2).toSet())
    }

    @Test
    fun `joining a locked group locks the newcomer without moving the board`() {
        val board = scatteredBoard()
        moveTo(board, 0, 0f, 0f)
        board.drop(0, snapToBoard = true)
        moveTo(board, 2, 0.15f, 1.1f)
        assertTrue(board.drop(2, snapToBoard = true))
        assertTrue(board.isLocked(2))
        assertEquals(0f, board.x(0), 0f)
        assertEquals(0f, board.x(2), 1e-5f)
        assertEquals(1f, board.y(2), 1e-5f)
    }

    @Test
    fun `solved once every piece is in one group, wherever it lies`() {
        val board = scatteredBoard()
        val offsets = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)
        for (i in 0 until board.size) {
            assertFalse(board.isSolved)
            val (dx, dy) = offsets[i]
            moveTo(board, i, 30f + dx, 40f + dy)
            board.drop(i, snapToBoard = false)
        }
        assertTrue(board.isSolved)
        assertFalse(board.isLocked(0))
    }

    @Test
    fun `two halves can be joined flush against the screen edge`() {
        // Screen exactly as wide as the puzzle: the solved image must still fit edge to edge.
        val board = PuzzleBoard(1, 4)
        for (i in 0 until 4) moveTo(board, i, 10f * i + 10f, 5f + 3f * i)
        moveTo(board, 0, 0f, 5f)
        moveTo(board, 1, 1.05f, 5f)
        board.drop(1, snapToBoard = false)
        moveTo(board, 2, 20f, 5f)
        moveTo(board, 3, 21.05f, 5f)
        board.drop(3, snapToBoard = false)
        moveTo(board, 2, 2.1f, 5.05f) // right half pokes 0.1 past the right edge
        board.keepGroupReachable(2, Bounds(0f, 0f, 4f, 10f))
        board.drop(2, snapToBoard = false)
        assertTrue(board.isSolved)
        assertEquals(0f, board.x(0), 1e-5f)
        assertEquals(3f, board.x(3), 1e-5f)
    }

    @Test
    fun `keepGroupReachable only pulls back groups that left the screen`() {
        val board = scatteredBoard()
        moveTo(board, 1, 11f, 10f)
        board.drop(1, snapToBoard = false) // group spans x 10..12, y 10..11
        board.keepGroupReachable(0, Bounds(0f, 0f, 11.5f, 30f))
        assertEquals(10f, board.x(0), 1e-5f) // partly off-screen is fine
        board.keepGroupReachable(0, Bounds(0f, 0f, 9f, 30f))
        assertEquals(8.5f, board.x(0), 1e-5f) // fully off the right edge: half a cell comes back
        board.keepGroupReachable(0, Bounds(0f, 12f, 30f, 30f))
        assertEquals(11.5f, board.y(0), 1e-5f) // above the top edge
    }

    @Test
    fun `scatter keeps whole cells within bounds`() {
        val board = PuzzleBoard(4, 5)
        board.scatter(Bounds(-2f, 5f, 6f, 9f), Random(42))
        for (i in 0 until board.size) {
            assertTrue(board.x(i) >= -2f && board.x(i) + 1f <= 6f)
            assertTrue(board.y(i) >= 5f && board.y(i) + 1f <= 9f)
        }
    }

    @Test
    fun `bringToFront raises the entire group`() {
        val board = scatteredBoard()
        moveTo(board, 3, 31f, 31f)
        moveTo(board, 1, 31f, 30f)
        board.drop(1, snapToBoard = false)
        assertTrue(board.sameGroup(1, 3))
        board.bringToFront(3)
        assertEquals(setOf(1, 3), board.drawOrder.takeLast(2).toSet())
    }
}
