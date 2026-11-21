package dev.daniel.jigsaw.puzzle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PileFrameTest {
    private val snapshot = BoardSnapshot(
        xs = listOf(0f, 4f),
        ys = listOf(0f, 2f),
        groups = listOf(0, 1),
        locked = listOf(false, false),
        order = listOf(0, 1),
        turns = listOf(1, 3),
    )

    @Test
    fun `quarter-turned pieces on non-square cells widen the frame to their turned extent`() {
        // Cells 3 wide × 4 tall: a quarter-turned piece spans (0.5 + PAD) · 4/3 cell widths each side.
        val frame = pileFrame(snapshot, cellWidth = 3f, cellHeight = 4f)
        val halfX = (0.5f + JigsawCut.PAD) * 4f / 3f
        val halfY = (0.5f + JigsawCut.PAD) * 3f / 4f
        assertEquals(0.5f - halfX, frame.minX, 1e-5f)
        assertEquals(4.5f + halfX, frame.maxX, 1e-5f)
        assertEquals(0.5f - halfY, frame.minY, 1e-5f)
        assertEquals(2.5f + halfY, frame.maxY, 1e-5f)
    }

    @Test
    fun `upright pieces keep the plain padded frame`() {
        val frame = pileFrame(snapshot.copy(turns = listOf(0, 0)), cellWidth = 3f, cellHeight = 4f)
        assertEquals(-JigsawCut.PAD, frame.minX, 1e-5f)
        assertEquals(-JigsawCut.PAD, frame.minY, 1e-5f)
        assertEquals(5f + JigsawCut.PAD, frame.maxX, 1e-5f)
        assertEquals(3f + JigsawCut.PAD, frame.maxY, 1e-5f)
    }
}
