package dev.daniel.jigsaw.puzzle

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PuzzleStoreTest {
    @TempDir
    lateinit var tmp: File

    private val inProgress = SavedPuzzle.InProgress(
        grid = Grid(rows = 2, cols = 2),
        seed = -1234567890123L,
        board = BoardSnapshot(
            xs = listOf(0.5f, -3.25f, 7f, 1f),
            ys = listOf(10f, 2.75f, -0.125f, 1f),
            groups = listOf(0, 0, 2, 3),
            locked = listOf(false, false, true, false),
            order = listOf(2, 3, 0, 1),
        ),
        pieces = PieceCount.P48,
    )

    private val rotated = inProgress.copy(board = inProgress.board.copy(turns = listOf(3, 2, 0, 1), rotatable = true))

    @Test
    fun `saves from before the piece-count setting load as cut for the default count`() {
        val legacy = encodedAndEdited(inProgress) { remove("pieces") }
        assertEquals(inProgress.copy(pieces = PieceCount.DEFAULT), PuzzleStore.decode(legacy))
    }

    @Test
    fun `saves cut for a piece count this version does not offer keep their board and load as the default count`() {
        for (pieces in listOf(37, 192)) {
            val unoffered = encodedAndEdited(inProgress) { put("pieces", JsonPrimitive(pieces)) }
            assertEquals(inProgress.copy(pieces = PieceCount.DEFAULT), PuzzleStore.decode(unoffered), "$pieces")
        }
    }

    @Test
    fun `rotatable boards keep their turns through encode and decode`() {
        assertEquals(rotated, PuzzleStore.decode(PuzzleStore.encode(rotated)))
    }

    @Test
    fun `rotatable boards keep their turns through save and load`() {
        PuzzleStore(tmp).save(PuzzleKey("s", "img"), rotated)
        assertEquals(rotated, PuzzleStore(tmp).load(PuzzleKey("s", "img")))
    }

    @Test
    fun `saves from before rotation load as an upright, non-rotatable board`() {
        val legacy = encodedAndEdited(rotated) {
            remove("turns")
            remove("rotatable")
        }
        val decoded = PuzzleStore.decode(legacy) as SavedPuzzle.InProgress
        assertEquals(rotated.board.copy(turns = emptyList(), rotatable = false), decoded.board)

        val board = PuzzleBoard(decoded.grid, decoded.board)
        assertFalse(board.rotatable)
        assertEquals(List(board.size) { 0 }, List(board.size) { board.turns(it) })
    }

    @Test
    fun `saves are kept per source and image and survive a new store instance`() {
        PuzzleStore(tmp).apply {
            save(PuzzleKey("source-a", "/photos/beach.jpg"), inProgress)
            save(PuzzleKey("source-a", "/photos/forest.jpg"), SavedPuzzle.Solved)
        }
        val reopened = PuzzleStore(tmp)
        assertEquals(inProgress, reopened.load(PuzzleKey("source-a", "/photos/beach.jpg")))
        assertEquals(SavedPuzzle.Solved, reopened.load(PuzzleKey("source-a", "/photos/forest.jpg")))
        assertNull(reopened.load(PuzzleKey("source-b", "/photos/beach.jpg")))
    }

    @Test
    fun `a later save replaces the earlier one`() {
        val store = PuzzleStore(tmp)
        store.save(PuzzleKey("s", "img"), inProgress)
        store.save(PuzzleKey("s", "img"), SavedPuzzle.Solved)
        assertEquals(SavedPuzzle.Solved, store.load(PuzzleKey("s", "img")))
    }

    @Test
    fun `clear forgets one image, clearSource forgets a whole source`() {
        val store = PuzzleStore(tmp)
        store.save(PuzzleKey("s1", "a"), inProgress)
        store.save(PuzzleKey("s1", "b"), inProgress)
        store.save(PuzzleKey("s2", "a"), inProgress)
        store.clear(PuzzleKey("s1", "a"))
        assertNull(store.load(PuzzleKey("s1", "a")))
        assertEquals(inProgress, store.load(PuzzleKey("s1", "b")))
        store.clearSource("s1")
        assertNull(store.load(PuzzleKey("s1", "b")))
        assertEquals(inProgress, store.load(PuzzleKey("s2", "a")))
    }

    @Test
    fun `saves written by earlier versions load, and new saves keep every field they read`() {
        // Byte for byte what the org.json encoder wrote on a device: floats widened to doubles, integral ones bare.
        val v1 = """{"version":1,"solved":false,"rows":2,"cols":2,"seed":-1234567890123,"pieces":48,""" +
            """"xs":[0.10000000149011612,-3.25,7,0.3333333432674408],"ys":[10,2.75,-0.125,1],""" +
            """"groups":[0,0,2,3],"locked":[false,false,true,false],"order":[2,3,0,1],"turns":[3,2,0,1],""" +
            """"rotatable":true}"""
        val expected = rotated.copy(board = rotated.board.copy(xs = listOf(0.1f, -3.25f, 7f, 1f / 3)))
        assertEquals(expected, PuzzleStore.decode(v1))
        assertEquals(
            Json.parseToJsonElement(v1).jsonObject.keys.toList(),
            Json.parseToJsonElement(PuzzleStore.encode(expected)).jsonObject.keys.toList(),
        )

        val solved = """{"version":1,"solved":true}"""
        assertEquals(SavedPuzzle.Solved, PuzzleStore.decode(solved))
        assertEquals(solved, PuzzleStore.encode(SavedPuzzle.Solved))
    }

    @Test
    fun `unreadable, foreign-format or inconsistent saves load as null`() {
        val store = PuzzleStore(tmp)
        val key = PuzzleKey("s", "img")
        store.save(key, inProgress)
        val file = tmp.walkTopDown().single { it.isFile }
        val inconsistent = encodedAndEdited(inProgress) { put("xs", numbers(0.0)) }
        for (json in listOf("not json", """{"version": 99, "solved": true}""", inconsistent)) {
            file.writeText(json)
            assertNull(store.load(key), json)
        }
    }

    @Test
    fun `unreadable or foreign-format saves fail to decode`() {
        assertThrows<SerializationException> { PuzzleStore.decode("not json") }
        assertThrows<SerializationException> { PuzzleStore.decode("""{"version": 99, "solved": true}""") }
        assertThrows<SerializationException> { PuzzleStore.decode("""{"version": 1, "solved": false, "rows": 2}""") }
    }

    @Test
    fun `saves whose board does not fit their grid fail to decode`() {
        fun tampered(edit: MutableMap<String, JsonElement>.() -> Unit) = assertThrows<IllegalArgumentException> {
            PuzzleStore.decode(encodedAndEdited(inProgress, edit))
        }

        fun MutableMap<String, JsonElement>.noPieces() = apply {
            for (key in listOf("xs", "ys", "groups", "locked", "order", "turns")) put(key, numbers())
        }

        fun MutableMap<String, JsonElement>.grid(rows: Int, cols: Int) = apply {
            put("rows", JsonPrimitive(rows))
            put("cols", JsonPrimitive(cols))
        }

        tampered { noPieces() }
        tampered { put("xs", numbers(0.0, 1.0, 2.0)) }
        tampered { put("turns", numbers(1, 2)) }
        tampered { put("order", numbers(0, 1, 2, 4)) }
        tampered { put("order", numbers(0, 1, 1, 2)) }
        tampered { put("groups", numbers(0, 0, 2, 4)) }
        // −2 × −2 = 4 matches the list sizes, but is no grid.
        tampered { grid(-2, -2) }
        tampered { noPieces().put("rows", JsonPrimitive(0)) }
        // 65536 × 65536 overflows Int to 0, which must not pass for empty lists.
        tampered { noPieces().grid(65536, 65536) }
    }

    @Test
    fun `legacy preview images are removed while saves stay`() {
        val store = PuzzleStore(tmp)
        store.save(PuzzleKey("s", "img"), inProgress)
        val sourceDir = tmp.listFiles()!!.single()
        val legacy = sourceDir.resolve("old-preview.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        store.deleteLegacyPreviews()
        assertFalse(legacy.exists())
        assertEquals(inProgress, store.load(PuzzleKey("s", "img")))
    }

    private fun encodedAndEdited(puzzle: SavedPuzzle, edit: MutableMap<String, JsonElement>.() -> Unit): String {
        val fields = Json.parseToJsonElement(PuzzleStore.encode(puzzle)).jsonObject.toMutableMap()
        return JsonObject(fields.apply(edit)).toString()
    }

    private fun numbers(vararg values: Number) = JsonArray(values.map { JsonPrimitive(it) })
}
