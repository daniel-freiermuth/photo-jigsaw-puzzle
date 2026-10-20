package dev.daniel.jigsaw.source

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ImageListStoreTest {
    @TempDir
    lateinit var tmp: File

    private val listings = mapOf(
        "im" to listOf(
            RemoteImage(
                id = "a1",
                name = "a b.jpg",
                thumbnail = HttpResource("https://p/api/assets/a1/thumbnail?key=k&size=thumbnail", revision = "h1"),
                full = HttpResource("https://p/api/assets/a1/thumbnail", mapOf("X-Test" to "1"), "h1"),
                takenAt = 1_714_564_800_000,
            ),
        ),
        "folder" to listOf(
            RemoteImage("d", "d.png", DocumentResource("content://p/d", "5:9"), DocumentResource("content://p/d")),
        ),
        "empty" to emptyList(),
    )

    @Test
    fun `saved listings come back intact, including headers, revisions and missing dates`() = runTest {
        val store = ImageListStore(File(tmp, "gallery/listings.json"), StandardTestDispatcher(testScheduler))
        store.save(listings)
        assertEquals(listings, store.load())
    }

    @Test
    fun `the file format tags each picture with its kind`() {
        val v2 = """{"version":2,"sources":{"im":[{"id":"a1","name":"a b.jpg",""" +
            """"thumbnail":{"type":"http","url":"https://p/api/assets/a1/thumbnail?key=k&size=thumbnail",""" +
            """"headers":{},"revision":"h1"},""" +
            """"full":{"type":"http","url":"https://p/api/assets/a1/thumbnail","headers":{"X-Test":"1"},""" +
            """"revision":"h1"},"takenAt":1714564800000}],""" +
            """"folder":[{"id":"d","name":"d.png","thumbnail":{"type":"document","uri":"content://p/d",""" +
            """"revision":"5:9"},"full":{"type":"document","uri":"content://p/d"}}],"empty":[]}}"""
        assertEquals(listings, ImageListStore.decode(v2))
        assertEquals(Json.parseToJsonElement(v2), Json.parseToJsonElement(ImageListStore.encode(listings)))
    }

    @Test
    fun `listings of the previous format are dropped, to be listed anew`() {
        val v1 = """{"version":1,"sources":{"u":[{"id":"u","name":"u",""" +
            """"thumbnail":{"url":"https://e/u.png","headers":{}},"full":{"url":"https://e/u.png","headers":{}}}]}}"""
        assertEquals(emptyMap<String, List<RemoteImage>>(), ImageListStore.decode(v1))
    }

    @Test
    fun `a missing or corrupt file means no saved listings`() = runTest {
        val file = File(tmp, "listings.json")
        val store = ImageListStore(file, StandardTestDispatcher(testScheduler))
        assertEquals(emptyMap<String, List<RemoteImage>>(), store.load())
        file.writeText("{not json")
        assertEquals(emptyMap<String, List<RemoteImage>>(), store.load())
    }
}
