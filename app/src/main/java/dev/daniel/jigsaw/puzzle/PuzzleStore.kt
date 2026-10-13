package dev.daniel.jigsaw.puzzle

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** What is remembered about one image's puzzle. */
sealed interface SavedPuzzle {
    /** Finished: only the fact is kept, the board is dropped. */
    data object Solved : SavedPuzzle

    /** [seed] regenerates the identical cut for a [rows] × [cols] grid; [board] holds the piece state. */
    data class InProgress(val rows: Int, val cols: Int, val seed: Long, val board: BoardSnapshot) : SavedPuzzle
}

/**
 * Persists puzzle progress as one small JSON file per image under [root], grouped by source so that
 * removing a source can drop all of its puzzles. Blocking I/O: call off the main thread, and from a
 * single thread so that writes and reads of the same puzzle stay ordered.
 */
class PuzzleStore(private val root: File) {

    fun load(sourceId: String, imageId: String): SavedPuzzle? {
        val file = fileFor(sourceId, imageId)
        if (!file.exists()) return null
        return try {
            decode(file.readText())
        } catch (_: IOException) {
            null
        }
    }

    fun save(sourceId: String, imageId: String, puzzle: SavedPuzzle) {
        val file = fileFor(sourceId, imageId)
        file.parentFile?.mkdirs()
        // Write-then-rename so a crash mid-write never leaves a truncated save behind.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(encode(puzzle))
        if (!tmp.renameTo(file)) throw IOException("Could not save puzzle to $file")
    }

    fun clear(sourceId: String, imageId: String) {
        fileFor(sourceId, imageId).delete()
    }

    fun clearSource(sourceId: String) {
        File(root, hash(sourceId)).deleteRecursively()
    }

    // Hashed names: ids are arbitrary strings (paths, URLs) and must not escape the directory.
    private fun fileFor(sourceId: String, imageId: String) = File(File(root, hash(sourceId)), hash(imageId) + ".json")

    private fun hash(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val FORMAT_VERSION = 1

        fun encode(puzzle: SavedPuzzle): String = JSONObject().apply {
            put("version", FORMAT_VERSION)
            when (puzzle) {
                SavedPuzzle.Solved -> put("solved", true)
                is SavedPuzzle.InProgress -> {
                    put("solved", false)
                    put("rows", puzzle.rows)
                    put("cols", puzzle.cols)
                    put("seed", puzzle.seed)
                    put("xs", JSONArray(puzzle.board.xs.map { it.toDouble() }))
                    put("ys", JSONArray(puzzle.board.ys.map { it.toDouble() }))
                    put("groups", JSONArray(puzzle.board.groups))
                    put("locked", JSONArray(puzzle.board.locked))
                    put("order", JSONArray(puzzle.board.order))
                }
            }
        }.toString()

        /** Null for unreadable or unknown-format saves, which are then simply started afresh. */
        fun decode(json: String): SavedPuzzle? = try {
            val o = JSONObject(json)
            when {
                o.optInt("version") != FORMAT_VERSION -> null
                o.getBoolean("solved") -> SavedPuzzle.Solved
                else -> SavedPuzzle.InProgress(
                    rows = o.getInt("rows"),
                    cols = o.getInt("cols"),
                    seed = o.getLong("seed"),
                    board = BoardSnapshot(
                        xs = o.getJSONArray("xs").map { getDouble(it).toFloat() },
                        ys = o.getJSONArray("ys").map { getDouble(it).toFloat() },
                        groups = o.getJSONArray("groups").map { getInt(it) },
                        locked = o.getJSONArray("locked").map { getBoolean(it) },
                        order = o.getJSONArray("order").map { getInt(it) },
                    ),
                )
            }
        } catch (_: JSONException) {
            null
        }

        private inline fun <T> JSONArray.map(read: JSONArray.(Int) -> T): List<T> = (0 until length()).map { read(it) }
    }
}
