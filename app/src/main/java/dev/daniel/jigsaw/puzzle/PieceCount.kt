package dev.daniel.jigsaw.puzzle

/** Piece counts offered in Settings. Approximate: the grid adapts to the picture's aspect ratio. */
// Each literal is the entry's whole meaning; naming it would only repeat the entry name.
@Suppress("MagicNumber")
enum class PieceCount(val target: Int) {
    P12(12),
    P24(24),
    P48(48),
    P96(96),
    ;

    companion object {
        /** Pieces per puzzle unless set otherwise. */
        val DEFAULT = P24

        /** Parses a stored [target]; counts that are not offered fall back to [DEFAULT]. */
        fun fromTarget(target: Int) = entries.firstOrNull { it.target == target } ?: DEFAULT
    }
}
