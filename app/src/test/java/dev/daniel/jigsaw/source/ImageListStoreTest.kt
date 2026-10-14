package dev.daniel.jigsaw.source

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageListStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `saved listings come back intact, including auth headers, revisions and missing dates`() {
        val listings = mapOf(
            "nc" to listOf(
                RemoteImage(
                    id = "/a b.jpg",
                    name = "a b.jpg",
                    thumbnail = HttpResource("https://c/preview?file=%2Fa%20b.jpg", revision = "\"e1\""),
                    full = HttpResource("https://c/dav/a%20b.jpg", mapOf("Authorization" to "Basic eDp5"), "\"e1\""),
                    takenAt = 1_714_564_800_000,
                ),
            ),
            "urls" to listOf(RemoteImage("u", "u", HttpResource("https://e/u.png"), HttpResource("https://e/u.png"))),
            "empty" to emptyList(),
        )
        val store = ImageListStore(File(tmp.root, "gallery/listings.json"))
        store.save(listings)
        assertEquals(listings, store.load())
    }

    @Test
    fun `a missing or corrupt file means no saved listings`() {
        val file = File(tmp.root, "listings.json")
        assertEquals(emptyMap<String, List<RemoteImage>>(), ImageListStore(file).load())
        file.writeText("{not json")
        assertEquals(emptyMap<String, List<RemoteImage>>(), ImageListStore(file).load())
    }
}
