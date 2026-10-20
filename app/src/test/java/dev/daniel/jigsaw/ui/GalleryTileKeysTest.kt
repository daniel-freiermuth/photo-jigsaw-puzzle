package dev.daniel.jigsaw.ui

import dev.daniel.jigsaw.puzzle.CanvasSpec
import dev.daniel.jigsaw.puzzle.PuzzleSettings
import dev.daniel.jigsaw.source.HttpResource
import dev.daniel.jigsaw.source.RemoteImage
import dev.daniel.jigsaw.source.SourceConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class GalleryTileKeysTest {
    private val canvas = CanvasSpec(width = 1080f, height = 1920f, margin = 40f)
    private val settings = PuzzleSettings()

    private val url = "https://p/cat.jpg"
    private val picture = RemoteImage(url, "cat.jpg", HttpResource(url), HttpResource(url))

    private fun listed(sourceId: String) =
        GalleryImage(SourceConfig.ImmichShare(sourceId, "Album", "https://p/share/k"), picture)

    @Test
    fun `one picture listed by two sources gets an untouched tile per source`() {
        // The same link added twice: each item opens with its own seed, so each needs its own scatter.
        val first = listed("a")
        val second = listed("b")
        assertNotEquals(first.seed, second.seed)

        assertNotEquals(untouchedTileKey(first, canvas, settings), untouchedTileKey(second, canvas, settings))
    }

    @Test
    fun `the same item keeps its untouched tile`() {
        assertEquals(untouchedTileKey(listed("a"), canvas, settings), untouchedTileKey(listed("a"), canvas, settings))
    }
}
