package dev.daniel.jigsaw.puzzle

import android.util.Log
import dev.daniel.jigsaw.WireJson
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random

/** What is remembered about one image's puzzle. */
sealed interface SavedPuzzle {
    /** Finished: only the fact is kept, the board is dropped. */
    data object Solved : SavedPuzzle

    /**
     * [seed] regenerates the identical cut for [grid], which was chosen for about
     * [pieces] pieces; [board] holds the piece state. Throws [IllegalArgumentException] if [board]
     * does not fit that grid, so every instance is safe to render or restore.
     */
    data class InProgress(
        val grid: Grid,
        val seed: Long,
        val board: BoardSnapshot,
        val pieces: PieceCount = PieceCount.DEFAULT,
    ) : SavedPuzzle {
        init {
            board.requireFits(grid)
        }

        /** Whether a piece has been placed on the board yet. */
        val started get() = board.anyOnBoard(grid)

        /** A puzzle not [started] yet counts as new: if cut for another piece count, it is cut afresh. */
        fun needsRecut(settings: PuzzleSettings) = !started && pieces != settings.pieces

        /**
         * A puzzle not [started] yet still counts as new, so it follows the current rotation setting
         * (its pile is turned or straightened in place, keeping joined pieces joined). Seeded by
         * [seed], so the gallery tile and the opened puzzle agree.
         */
        fun withRotation(rotatePieces: Boolean, cellAspect: Float): InProgress =
            if (started || board.rotatable == rotatePieces) {
                this
            } else {
                copy(board = board.withRotatable(rotatePieces, grid, cellAspect, Random(seed)))
            }
    }
}

/** Identifies one image's puzzle: image ids are only unique within their source. */
data class PuzzleKey(val sourceId: String, val imageId: String)

/**
 * Persists puzzle progress as one small JSON file per image under [root], grouped by source so that
 * removing a source can drop all of its puzzles. Blocking I/O: call off the main thread, and from a
 * single thread so that writes and reads of the same puzzle stay ordered.
 */
class PuzzleStore(private val root: File) {

    /**
     * Null if [key] was never saved, or if its save is unreadable, from another format version or
     * inconsistent. The latter are logged: the puzzle then starts afresh and its first save replaces them.
     */
    fun load(key: PuzzleKey): SavedPuzzle? {
        val file = fileFor(key)
        if (!file.exists()) return null
        return try {
            decode(file.readText())
        } catch (e: IOException) {
            discarded(key, e)
        } catch (e: IllegalArgumentException) {
            // Also kotlinx.serialization's SerializationException: unreadable JSON, missing fields, other versions.
            discarded(key, e)
        }
    }

    private fun discarded(key: PuzzleKey, cause: Exception): SavedPuzzle? {
        Log.w(TAG, "Discarding unreadable saved puzzle $key, starting afresh", cause)
        return null
    }

    fun save(key: PuzzleKey, puzzle: SavedPuzzle) {
        val file = fileFor(key)
        file.parentFile?.mkdirs()
        // Write-then-rename so a crash mid-write never leaves a truncated save behind.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(encode(puzzle))
        if (!tmp.renameTo(file)) throw IOException("Could not save puzzle to $file")
    }

    fun clear(key: PuzzleKey) {
        fileFor(key).delete()
    }

    fun clearSource(sourceId: String) {
        File(root, hash(sourceId)).deleteRecursively()
    }

    /** Removes layout images written by earlier versions; tiles are now rendered from the saved state. */
    fun deleteLegacyPreviews() {
        root.walkTopDown().filter { it.isFile && it.name.endsWith(LEGACY_PREVIEW_SUFFIX) }.forEach { it.delete() }
    }

    // Hashed names: ids are arbitrary strings (paths, URLs) and must not escape the directory.
    private fun fileFor(key: PuzzleKey) = File(File(root, hash(key.sourceId)), hash(key.imageId) + ".json")

    private fun hash(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray())
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val LEGACY_PREVIEW_SUFFIX = ".png"
        private const val TAG = "PuzzleStore"

        fun encode(puzzle: SavedPuzzle): String = when (puzzle) {
            SavedPuzzle.Solved -> WireJson.encodeToString(SaveHeader(FORMAT_VERSION, solved = true))
            is SavedPuzzle.InProgress -> WireJson.encodeToString(InProgressSave.of(puzzle))
        }

        /**
         * Throws [SerializationException] for unparsable saves or ones from another format version, and a
         * plain [IllegalArgumentException] for saves whose board does not fit their grid.
         */
        fun decode(json: String): SavedPuzzle {
            val header = WireJson.decodeFromString<SaveHeader>(json)
            if (header.version != FORMAT_VERSION) {
                throw SerializationException("Unsupported save format version ${header.version}")
            }
            return if (header.solved) {
                SavedPuzzle.Solved
            } else {
                WireJson.decodeFromString<InProgressSave>(json).toSavedPuzzle()
            }
        }
    }

    /** The fields every save starts with; read first so that other format versions are never decoded further. */
    @Serializable
    private class SaveHeader(val version: Int, val solved: Boolean)

    /**
     * Save-file format 1 of a [SavedPuzzle.InProgress]: [SaveHeader] plus the board as flat lists. Every field is
     * always written so that older app versions read the save; the defaults fill in what older versions left out.
     */
    @Suppress("LongParameterList") // One parameter per field of the file.
    @Serializable
    private class InProgressSave(
        val version: Int,
        val solved: Boolean,
        val rows: Int,
        val cols: Int,
        val seed: Long,
        // Absent in saves from before the setting existed: those were all cut for the default.
        @EncodeDefault val pieces: Int = PieceCount.DEFAULT.target,
        val xs: List<Float>,
        val ys: List<Float>,
        val groups: List<Int>,
        val locked: List<Boolean>,
        val order: List<Int>,
        // Absent in saves from before rotation existed: all pieces upright.
        @EncodeDefault val turns: List<Int> = emptyList(),
        @EncodeDefault val rotatable: Boolean = false,
    ) {
        fun toSavedPuzzle() = SavedPuzzle.InProgress(
            grid = Grid(rows = rows, cols = cols),
            seed = seed,
            board = BoardSnapshot(xs, ys, groups, locked, order, turns, rotatable),
            pieces = PieceCount.fromTarget(pieces),
        )

        companion object {
            fun of(puzzle: SavedPuzzle.InProgress) = InProgressSave(
                version = FORMAT_VERSION,
                solved = false,
                rows = puzzle.grid.rows,
                cols = puzzle.grid.cols,
                seed = puzzle.seed,
                pieces = puzzle.pieces.target,
                xs = puzzle.board.xs,
                ys = puzzle.board.ys,
                groups = puzzle.board.groups,
                locked = puzzle.board.locked,
                order = puzzle.board.order,
                turns = puzzle.board.turns,
                rotatable = puzzle.board.rotatable,
            )
        }
    }
}
