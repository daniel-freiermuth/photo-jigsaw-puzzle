package dev.daniel.jigsaw.puzzle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SavedPuzzleTest {

    /** 2×3 puzzle scattered below the board, with pieces 0 and 1 joined into a group. */
    private fun unstarted(rotatable: Boolean): SavedPuzzle.InProgress {
        val board = PuzzleBoard(Grid(rows = 2, cols = 3), rotatable = rotatable)
        board.scatter(Bounds(0f, 5f, 12f, 12f), Random(3))
        val snapshot = board.snapshot
        // Join 0 and 1: put 1 right of 0 (in 0's orientation) and drop it there.
        val joined = PuzzleBoard(Grid(rows = 2, cols = 3), snapshot.copy(turns = List(6) { 0 })).apply {
            moveGroup(1, x(0) + 1f - x(1), y(0) - y(1))
            drop(1, snapToBoard = false)
        }.snapshot.copy(rotatable = rotatable)
        return SavedPuzzle.InProgress(grid = Grid(rows = 2, cols = 3), seed = 42L, board = joined)
    }

    @Test
    fun `an unstarted puzzle takes on rotation, keeping joined pieces joined and aligned`() {
        val saved = unstarted(rotatable = false)
        val turned = saved.withRotation(rotatePieces = true, cellAspect = 1f)
        assertTrue(turned.board.rotatable)
        assertEquals(turned.board.groups[0], turned.board.groups[1])
        assertEquals(turned.board.turns[0], turned.board.turns[1])
        // Turning back upright restores 1 to the right of 0.
        val upright = turned.withRotation(rotatePieces = false, cellAspect = 1f)
        assertFalse(upright.board.rotatable)
        assertTrue(upright.board.turns.all { it == 0 })
        assertEquals(upright.board.xs[0] + 1f, upright.board.xs[1], 1e-4f)
        assertEquals(upright.board.ys[0], upright.board.ys[1], 1e-4f)
    }

    @Test
    fun `the conversion is seeded, so gallery tile and opened puzzle agree`() {
        val saved = unstarted(rotatable = false)
        assertEquals(saved.withRotation(true, 1f), saved.withRotation(true, 1f))
    }

    @Test
    fun `turning a joined strip in the tray does not swing it onto the board`() {
        val grid = Grid(rows = 2, cols = 3)
        // Pieces 0, 1 and 2 joined into a strip in the tray's top row, just below the board; the rest loose below.
        val board = BoardSnapshot(
            xs = listOf(0f, 1f, 2f, 0f, 2f, 4f),
            ys = listOf(2.4f, 2.4f, 2.4f, 4f, 4f, 4f),
            groups = listOf(0, 0, 0, 3, 4, 5),
            locked = List(6) { false },
            order = (0 until 6).toList(),
        )
        // The strip's group comes first, so the first draw picks its turns: three quarters swing it upwards.
        val seed = (0L..100L).first { Random(it).nextInt(PuzzleBoard.QUARTERS) == 3 }
        val saved = SavedPuzzle.InProgress(grid = grid, seed = seed, board = board)
        assertFalse(saved.started)
        val turned = saved.withRotation(rotatePieces = true, cellAspect = 1f)
        assertTrue(turned.board.rotatable)
        assertFalse(turned.started)
        assertFalse(turned.withRotation(rotatePieces = false, cellAspect = 1f).board.rotatable)
    }

    @Test
    fun `a started puzzle or one already in the requested mode is left alone`() {
        val saved = unstarted(rotatable = false)
        assertSame(saved, saved.withRotation(rotatePieces = false, cellAspect = 1f))
        val onBoard = saved.copy(
            board = saved.board.copy(xs = listOf(0f) + saved.board.xs.drop(1)).let {
                it.copy(ys = listOf(0f) + it.ys.drop(1))
            },
        )
        assertTrue(onBoard.started)
        assertSame(onBoard, onBoard.withRotation(rotatePieces = true, cellAspect = 1f))
    }

    @Test
    fun `only an unstarted puzzle cut for another piece count needs a fresh cut`() {
        val saved = unstarted(rotatable = false).copy(pieces = PieceCount.P24)
        assertFalse(saved.needsRecut(PuzzleSettings(pieces = PieceCount.P24)))
        assertTrue(saved.needsRecut(PuzzleSettings(pieces = PieceCount.P48)))
        val onBoard = saved.copy(
            board = saved.board.copy(
                xs = listOf(0f) + saved.board.xs.drop(1),
                ys = listOf(0f) + saved.board.ys.drop(1),
            ),
        )
        assertFalse(onBoard.needsRecut(PuzzleSettings(pieces = PieceCount.P48)))
    }
}
