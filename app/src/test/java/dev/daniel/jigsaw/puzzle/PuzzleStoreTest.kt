package dev.daniel.jigsaw.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PuzzleStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

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
    )

    @Test
    fun `saves are kept per source and image and survive a new store instance`() {
        PuzzleStore(tmp.root).apply {
            save("source-a", "/photos/beach.jpg", inProgress)
            save("source-a", "/photos/forest.jpg", SavedPuzzle.Solved)
        }
        val reopened = PuzzleStore(tmp.root)
        assertEquals(inProgress, reopened.load("source-a", "/photos/beach.jpg"))
        assertEquals(SavedPuzzle.Solved, reopened.load("source-a", "/photos/forest.jpg"))
        assertNull(reopened.load("source-b", "/photos/beach.jpg"))
    }

    @Test
    fun `a later save replaces the earlier one`() {
        val store = PuzzleStore(tmp.root)
        store.save("s", "img", inProgress)
        store.save("s", "img", SavedPuzzle.Solved)
        assertEquals(SavedPuzzle.Solved, store.load("s", "img"))
    }

    @Test
    fun `clear forgets one image, clearSource forgets a whole source`() {
        val store = PuzzleStore(tmp.root)
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
}
