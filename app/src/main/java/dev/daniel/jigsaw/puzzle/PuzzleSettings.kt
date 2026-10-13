package dev.daniel.jigsaw.puzzle

/**
 * Puzzle preferences from Settings. [rotatePieces] and [pieces] shape how a puzzle is cut and laid
 * out, so they apply to new puzzles — including ones that have no piece on the board yet.
 */
data class PuzzleSettings(
    val snapToBoard: Boolean = false,
    val rotatePieces: Boolean = false,
    /** Approximate piece count, one of [JigsawCut.PIECE_COUNTS]. */
    val pieces: Int = JigsawCut.DEFAULT_PIECES,
)
