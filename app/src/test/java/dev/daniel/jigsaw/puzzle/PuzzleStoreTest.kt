package dev.daniel.jigsaw.puzzle

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PuzzleStoreTest {
    @TempDir
    lateinit var tmp: File

    private val inProgress = SavedPuzzle.InProgress(
        rows = 2,
        cols = 2,
        seed = -1234567890123L,
        board = BoardSnapshot(
            xs = listOf(0.5f, -3.25f, 7f, 1f),
            ys = listOf(10f, 2.75f, -0.125f, 1f),
            groups = listOf(0, 0, 2, 3),
            locked = listOf(false, false, true, false),
            order = listOf(2, 3, 0, 1),
        ),
        pieces = 48,
    )

    private val rotated = inProgress.copy(board = inProgress.board.copy(turns = listOf(3, 2, 0, 1), rotatable = true))

    @Test
    fun `saves from before the piece-count setting load as cut for the default count`() {
        val legacy = JSONObject(PuzzleStore.encode(inProgress)).apply { remove("pieces") }.toString()
        assertEquals(inProgress.copy(pieces = JigsawCut.DEFAULT_PIECES), PuzzleStore.decode(legacy))
    }

    @Test
    fun `rotatable boards keep their turns through encode and decode`() {
        assertEquals(rotated, PuzzleStore.decode(PuzzleStore.encode(rotated)))
    }

    @Test
    fun `rotatable boards keep their turns through save and load`() {
        PuzzleStore(tmp).save("s", "img", rotated)
        assertEquals(rotated, PuzzleStore(tmp).load("s", "img"))
    }

    @Test
    fun `saves from before rotation load as an upright, non-rotatable board`() {
        val legacy = JSONObject(PuzzleStore.encode(rotated)).apply {
            remove("turns")
            remove("rotatable")
        }.toString()
        val decoded = PuzzleStore.decode(legacy) as SavedPuzzle.InProgress
        assertEquals(rotated.board.copy(turns = emptyList(), rotatable = false), decoded.board)

        val board = PuzzleBoard(decoded.rows, decoded.cols, decoded.board)
        assertFalse(board.rotatable)
        assertEquals(List(board.size) { 0 }, List(board.size) { board.turns(it) })
    }

    @Test
    fun `saves are kept per source and image and survive a new store instance`() {
        PuzzleStore(tmp).apply {
            save("source-a", "/photos/beach.jpg", inProgress)
            save("source-a", "/photos/forest.jpg", SavedPuzzle.Solved)
        }
        val reopened = PuzzleStore(tmp)
        assertEquals(inProgress, reopened.load("source-a", "/photos/beach.jpg"))
        assertEquals(SavedPuzzle.Solved, reopened.load("source-a", "/photos/forest.jpg"))
        assertNull(reopened.load("source-b", "/photos/beach.jpg"))
    }

    @Test
    fun `a later save replaces the earlier one`() {
        val store = PuzzleStore(tmp)
        store.save("s", "img", inProgress)
        store.save("s", "img", SavedPuzzle.Solved)
        assertEquals(SavedPuzzle.Solved, store.load("s", "img"))
    }

    @Test
    fun `clear forgets one image, clearSource forgets a whole source`() {
        val store = PuzzleStore(tmp)
        store.save("s1", "a", inProgress)
        store.save("s1", "b", inProgress)
        store.save("s2", "a", inProgress)
        store.clear("s1", "a")
        assertNull(store.load("s1", "a"))
        assertEquals(inProgress, store.load("s1", "b"))
        store.clearSource("s1")
        assertNull(store.load("s1", "b"))
        assertEquals(inProgress, store.load("s2", "a"))
    }

    @Test
    fun `unreadable or foreign-format saves decode to null`() {
        assertNull(PuzzleStore.decode("not json"))
        assertNull(PuzzleStore.decode("""{"version": 99, "solved": true}"""))
        assertNull(PuzzleStore.decode("""{"version": 1, "solved": false, "rows": 2}"""))
    }

    @Test
    fun `saves whose board does not fit their grid decode to null`() {
        fun tampered(edit: JSONObject.() -> Unit) =
            PuzzleStore.decode(JSONObject(PuzzleStore.encode(inProgress)).apply(edit).toString())

        fun JSONObject.noPieces() = apply {
            for (key in listOf("xs", "ys", "groups", "locked", "order", "turns")) put(key, JSONArray())
        }

        assertNull(tampered { noPieces() })
        assertNull(tampered { put("xs", JSONArray(listOf(0.0, 1.0, 2.0))) })
        assertNull(tampered { put("turns", JSONArray(listOf(1, 2))) })
        assertNull(tampered { put("order", JSONArray(listOf(0, 1, 2, 4))) })
        assertNull(tampered { put("order", JSONArray(listOf(0, 1, 1, 2))) })
        assertNull(tampered { put("groups", JSONArray(listOf(0, 0, 2, 4))) })
        // −2 × −2 = 4 matches the list sizes, but is no grid.
        assertNull(tampered { put("rows", -2).put("cols", -2) })
        assertNull(tampered { noPieces().put("rows", 0) })
        // 65536 × 65536 overflows Int to 0, which must not pass for empty lists.
        assertNull(tampered { noPieces().put("rows", 65536).put("cols", 65536) })
    }

    @Test
    fun `legacy preview images are removed while saves stay`() {
        val store = PuzzleStore(tmp)
        store.save("s", "img", inProgress)
        val sourceDir = tmp.listFiles()!!.single()
        val legacy = sourceDir.resolve("old-preview.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        store.deleteLegacyPreviews()
        assertFalse(legacy.exists())
        assertEquals(inProgress, store.load("s", "img"))
    }
}
