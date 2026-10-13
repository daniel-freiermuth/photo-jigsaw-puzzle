package dev.daniel.jigsaw.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SavedPuzzleTest {

    /** 2×3 puzzle scattered below the board, with pieces 0 and 1 joined into a group. */
    private fun unstarted(rotatable: Boolean): SavedPuzzle.InProgress {
        val board = PuzzleBoard(2, 3, rotatable = rotatable)
        board.scatter(Bounds(0f, 5f, 12f, 12f), Random(3))
        val snapshot = board.snapshot
        // Join 0 and 1: put 1 right of 0 (in 0's orientation) and drop it there.
        val joined = PuzzleBoard(2, 3, snapshot.copy(turns = List(6) { 0 })).apply {
            moveGroup(1, x(0) + 1f - x(1), y(0) - y(1))
            drop(1, snapToBoard = false)
        }.snapshot.copy(rotatable = rotatable)
        return SavedPuzzle.InProgress(rows = 2, cols = 3, seed = 42L, board = joined)
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
    fun `a started puzzle or one already in the requested mode is left alone`() {
        val saved = unstarted(rotatable = false)
        assertSame(saved, saved.withRotation(rotatePieces = false, cellAspect = 1f))
        val onBoard = saved.copy(
            board = saved.board.copy(xs = listOf(0f) + saved.board.xs.drop(1)).let {
                it.copy(ys = listOf(0f) + it.ys.drop(1))
            }
        )
        assertTrue(onBoard.started)
        assertSame(onBoard, onBoard.withRotation(rotatePieces = true, cellAspect = 1f))
    }
}
