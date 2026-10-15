package dev.daniel.jigsaw.puzzle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class PuzzleBoardTest {

    /** 2×2 board with every piece scattered far from the board and from each other. */
    private fun scatteredBoard() = PuzzleBoard(Grid(rows = 2, cols = 2)).apply {
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
    fun `a piece dropped between two loose groups joins both in one drop`() {
        val board = PuzzleBoard(Grid(rows = 1, cols = 3))
        moveTo(board, 0, 10f, 10f)
        moveTo(board, 2, 12.1f, 10.05f) // slightly off from where it belongs next to piece 1
        moveTo(board, 1, 11.1f, 10f)
        assertTrue(board.drop(1, snapToBoard = false))
        assertTrue(board.sameGroup(0, 1) && board.sameGroup(1, 2))
        assertEquals(board.x(0) + 1f, board.x(1), 1e-5f)
        assertEquals(board.x(0) + 2f, board.x(2), 1e-5f)
        assertEquals(board.y(0), board.y(1), 1e-5f)
        assertEquals(board.y(0), board.y(2), 1e-5f)
        assertFalse(board.isLocked(0) || board.isLocked(1) || board.isLocked(2))
    }

    @Test
    fun `a loose neighbour joining a group locked earlier in the same drop is pulled onto the board`() {
        val board = PuzzleBoard(Grid(rows = 1, cols = 3))
        moveTo(board, 1, 10f, 10f)
        moveTo(board, 2, 2.1f, 0.05f) // slightly off its solved cell; too far from piece 0 to join it
        assertTrue(board.drop(0, snapToBoard = true)) // piece 0 locks on its solved cell
        moveTo(board, 1, 1.1f, 0f)
        // Piece 1 first joins the locked piece 0, then the now-locked group must pull piece 2 onto it.
        assertTrue(board.drop(1, snapToBoard = true))
        for (i in 0 until board.size) {
            assertEquals(i.toFloat(), board.x(i), 1e-5f)
            assertEquals(0f, board.y(i), 1e-5f)
            assertTrue(board.isLocked(i))
        }
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
        val board = PuzzleBoard(Grid(rows = 1, cols = 4))
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
        val board = PuzzleBoard(Grid(rows = 4, cols = 5))
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

    @Test
    fun `restored board continues exactly where the snapshot left off`() {
        val board = scatteredBoard()
        moveTo(board, 0, 0f, 0f)
        board.drop(0, snapToBoard = true) // locked on the board
        moveTo(board, 3, 31f, 31f)
        moveTo(board, 1, 31f, 30.1f)
        board.drop(1, snapToBoard = false) // 1 and 3 joined, loose
        board.bringToFront(2)

        val restored = PuzzleBoard(Grid(rows = 2, cols = 2), board.snapshot)
        assertEquals(board.snapshot, restored.snapshot)
        assertTrue(restored.isLocked(0))
        assertTrue(restored.sameGroup(1, 3))
        // Groups still behave as groups after restoring: moving 1 carries 3 along.
        restored.moveGroup(1, 2f, 0f)
        assertEquals(33f, restored.x(3), 1e-5f)
        assertEquals(2, restored.drawOrder.last())
    }

    @Test
    fun `restoring a snapshot from another grid size is rejected`() {
        val twoByTwo = PuzzleBoard(Grid(rows = 2, cols = 2)).snapshot
        assertThrows<IllegalArgumentException> { PuzzleBoard(Grid(rows = 3, cols = 2), twoByTwo) }
    }

    @Test
    fun `restoring a snapshot with a broken draw order is rejected`() {
        assertThrows<IllegalArgumentException> {
            val grid = Grid(rows = 2, cols = 2)
            PuzzleBoard(grid, PuzzleBoard(grid).snapshot.copy(order = listOf(0, 0, 1, 2)))
        }
    }

    /** 1×2 rotatable board, pieces far apart and upright. */
    private fun rotatableBoard(cellAspect: Float = 1f) =
        PuzzleBoard(Grid(rows = 1, cols = 2), cellAspect = cellAspect, rotatable = true).apply {
            moveTo(this, 0, 10f, 10f)
            moveTo(this, 1, 20f, 10f)
        }

    @Test
    fun `four quarter turns bring a group back exactly`() {
        val board = rotatableBoard(cellAspect = 1.5f)
        moveTo(board, 1, 11f, 10f)
        board.drop(1, snapToBoard = false)
        val before = board.snapshot
        repeat(4) { board.rotateGroup(1) }
        val after = board.snapshot
        assertEquals(before.turns, after.turns)
        before.xs.zip(after.xs).forEach { (b, a) -> assertEquals(b, a, 1e-4f) }
        before.ys.zip(after.ys).forEach { (b, a) -> assertEquals(b, a, 1e-4f) }
    }

    @Test
    fun `a turned group keeps its picture proportions on non-square cells`() {
        // Cells twice as wide as tall: the right neighbour, one cell (two units of height) to the right,
        // ends up two rows below after a clockwise quarter turn around piece 0.
        val board = rotatableBoard(cellAspect = 2f)
        moveTo(board, 1, 11f, 10f)
        board.drop(1, snapToBoard = false)
        board.rotateGroup(0)
        assertEquals(1, board.turns(0))
        assertEquals(1, board.turns(1))
        assertEquals(10f, board.x(1), 1e-5f)
        assertEquals(12f, board.y(1), 1e-5f)
    }

    @Test
    fun `turned neighbours join only in the same orientation and turned position`() {
        val board = rotatableBoard()
        board.rotateGroup(0)
        // Piece 1 belongs to the right of 0 when upright; with both turned once it belongs below 0.
        moveTo(board, 1, 10f, 11.1f)
        assertFalse(board.drop(1, snapToBoard = false)) // 1 is still upright
        board.rotateGroup(1)
        assertTrue(board.drop(1, snapToBoard = false))
        assertTrue(board.sameGroup(0, 1))
        assertEquals(11f, board.y(1), 1e-5f)
    }

    @Test
    fun `only upright groups lock onto the board or count as solved`() {
        val board = rotatableBoard()
        moveTo(board, 1, 11f, 10f)
        board.drop(1, snapToBoard = false)
        board.rotateGroup(0)
        assertFalse(board.isSolved)
        moveTo(board, 0, 0f, 0f)
        assertFalse(board.drop(0, snapToBoard = true))
        repeat(3) { board.rotateGroup(0) }
        assertTrue(board.isSolved)
        moveTo(board, 0, 0.1f, 0f)
        assertTrue(board.drop(0, snapToBoard = true))
        assertTrue(board.isLocked(0))
    }

    @Test
    fun `boards without rotation ignore rotate requests and scatter upright`() {
        val board = PuzzleBoard(Grid(rows = 2, cols = 3))
        board.scatter(Bounds(0f, 0f, 10f, 10f), Random(1))
        board.rotateGroup(0)
        assertTrue(board.snapshot.turns.all { it == 0 })
        val rotatable = PuzzleBoard(Grid(rows = 2, cols = 3), rotatable = true)
        rotatable.scatter(Bounds(0f, 0f, 10f, 10f), Random(1))
        assertTrue(rotatable.snapshot.turns.any { it != 0 })
    }

    @Test
    fun `restoring keeps rotations, and saves from before rotation restore upright`() {
        val board = rotatableBoard()
        board.rotateGroup(1)
        assertEquals(board.snapshot, PuzzleBoard(Grid(rows = 1, cols = 2), board.snapshot).snapshot)
        val legacy = PuzzleBoard(Grid(rows = 1, cols = 2)).snapshot.copy(turns = emptyList())
        assertEquals(listOf(0, 0), PuzzleBoard(Grid(rows = 1, cols = 2), legacy).snapshot.turns)
    }

    @Test
    fun `pieces scattered for one canvas shape are pulled back onto the other`() {
        val portrait = boardLayout(CanvasSpec(1080f, 2100f, 12f), 4000f, 3000f, Grid(rows = 4, cols = 6))
        val landscape = boardLayout(CanvasSpec(2300f, 900f, 12f), 4000f, 3000f, Grid(rows = 4, cols = 6))
        for ((from, to) in listOf(portrait to landscape, landscape to portrait)) {
            val board = PuzzleBoard(Grid(rows = 4, cols = 6))
            board.scatter(from.scatter, Random(1))
            board.keepAllReachable(to.screen, to.scatter, Random(2))
            val s = to.screen
            for (i in 0 until board.size) {
                val x = board.x(i)
                val y = board.y(i)
                val visible = x + 1f > s.minX && x < s.maxX && y + 1f > s.minY && y < s.maxY
                assertTrue(visible, "piece $i at ($x, $y) is off $s")
            }
        }
    }

    @Test
    fun `pieces displaced by canvas shape changes land in the new tray, not on the board`() {
        val portrait = boardLayout(CanvasSpec(1080f, 2100f, 12f), 4000f, 3000f, Grid(rows = 4, cols = 6))
        val landscape = boardLayout(CanvasSpec(2300f, 900f, 12f), 4000f, 3000f, Grid(rows = 4, cols = 6))
        val board = PuzzleBoard(Grid(rows = 4, cols = 6))
        board.scatter(portrait.scatter, Random(1))
        for (to in listOf(landscape, portrait)) {
            board.keepAllReachable(to.screen, to.scatter, Random(2))
            val t = to.scatter
            for (i in 0 until board.size) {
                val x = board.x(i)
                val y = board.y(i)
                val inTray = x >= t.minX - 1e-4f && x + 1f <= t.maxX + 1e-4f &&
                    y >= t.minY - 1e-4f && y + 1f <= t.maxY + 1e-4f
                assertTrue(inTray, "piece $i at ($x, $y) is outside the tray $t")
            }
        }
    }

    private fun PuzzleBoard.isLocked(i: Int) = snapshot.locked[i]

    private fun PuzzleBoard.sameGroup(a: Int, b: Int) = snapshot.groups[a] == snapshot.groups[b]
}
