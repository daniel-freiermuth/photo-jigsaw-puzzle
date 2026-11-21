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
    fun `pictures whose URL or headers can't make a request are dropped`() {
        fun image(id: String, thumbnail: String, full: String = """{"type":"http","url":"https://e/$id.png"}""") =
            """{"id":"$id","name":"$id","thumbnail":$thumbnail,"full":$full}"""
        val good = """{"type":"http","url":"https://e/g.png"}"""
        val json = """{"version":2,"sources":{"s":[""" +
            image("ok", """{"type":"http","url":"https://e/ok.png","headers":{"X-Test":"1"}}""") + "," +
            image("url", """{"type":"http","url":"not a url"}""") + "," +
            image("name", """{"type":"http","url":"https://e/n.png","headers":{"bad name":"1"}}""") + "," +
            image("value", """{"type":"http","url":"https://e/v.png","headers":{"X":"a\nb"}}""") + "," +
            image("fullUrl", good, """{"type":"http","url":"not a url"}""") + "," +
            image("fullHeader", good, """{"type":"http","url":"https://e/f.png","headers":{"bad name":"1"}}""") +
            """]}}"""
        val decoded = ImageListStore.decode(json)
        assertEquals(listOf("ok"), decoded.getValue("s").map { it.id })
        decoded.getValue("s").forEach {
            Http.request(it.thumbnail as HttpResource)
            Http.request(it.full as HttpResource)
        }
    }

    @Test
    fun `documents whose uri isn't a content uri are dropped`() {
        fun image(id: String, uri: String) =
            """{"id":"$id","name":"$id","thumbnail":{"type":"document","uri":"$uri"},""" +
                """"full":{"type":"document","uri":"content://p/$id"}}"""
        val json = """{"version":2,"sources":{"s":[""" +
            image("ok", "content://p/ok") + "," + image("file", "file:x.jpg") + "," +
            image("bare", "file:") + "," + image("path", "file:///data/x.jpg") + """]}}"""
        assertEquals(listOf("ok"), ImageListStore.decode(json).getValue("s").map { it.id })
    }

    @Test
    fun `a picture repeating an id within its source is dropped, keeping the first`() {
        fun image(id: String, name: String) =
            """{"id":"$id","name":"$name","thumbnail":{"type":"http","url":"https://e/$name.png"},""" +
                """"full":{"type":"http","url":"https://e/$name.png"}}"""
        val json = """{"version":2,"sources":{"s":[""" +
            image("a", "first") + "," + image("b", "b") + "," + image("a", "second") +
            """],"t":[""" + image("a", "other") + """]}}"""
        val decoded = ImageListStore.decode(json)
        assertEquals(listOf("a" to "first", "b" to "b"), decoded.getValue("s").map { it.id to it.name })
        assertEquals(listOf("a"), decoded.getValue("t").map { it.id })
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
