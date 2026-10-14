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

    @Test
    fun `saved listings come back intact, including auth headers, revisions and missing dates`() = runTest {
        val store = ImageListStore(File(tmp, "gallery/listings.json"), StandardTestDispatcher(testScheduler))
        store.save(listings)
        assertEquals(listings, store.load())
    }

    @Test
    fun `listings saved by earlier versions load, and are written back in the same format`() {
        // Byte for byte what the org.json encoder wrote on a device (Android escapes `/`).
        val v1 = """{"version":1,"sources":{"nc":[{"id":"\/a b.jpg","name":"a b.jpg",""" +
            """"thumbnail":{"url":"https:\/\/c\/preview?file=%2Fa%20b.jpg","headers":{},"revision":"\"e1\""},""" +
            """"full":{"url":"https:\/\/c\/dav\/a%20b.jpg","headers":{"Authorization":"Basic eDp5"},""" +
            """"revision":"\"e1\""},"takenAt":1714564800000}],""" +
            """"urls":[{"id":"u","name":"u","thumbnail":{"url":"https:\/\/e\/u.png","headers":{}},""" +
            """"full":{"url":"https:\/\/e\/u.png","headers":{}}}],"empty":[]}}"""
        assertEquals(listings, ImageListStore.decode(v1))
        assertEquals(Json.parseToJsonElement(v1), Json.parseToJsonElement(ImageListStore.encode(listings)))
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
