package dev.daniel.jigsaw.source

import dev.daniel.jigsaw.WireJson
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class SourceParsingTest {

    @Test
    fun `immich share url picks key or slug auth`() {
        with(ImmichShareSource.parseShareUrl("https://photos.example.com/share/k3y")) {
            assertEquals("https://photos.example.com/", base.toString())
            assertEquals("key" to "k3y", authParam to authValue)
        }
        with(ImmichShareSource.parseShareUrl("https://ex.org/immich/s/summer")) {
            assertEquals("https://ex.org/immich/", base.toString())
            assertEquals("slug" to "summer", authParam to authValue)
        }
    }

    @Test
    fun `immich assets keep images only, with their capture time and thumbnail digest`() {
        val assets = WireJson.decodeFromString<List<ImmichShareSource.Asset>>(
            """[{"id":"a","type":"IMAGE","originalFileName":"a.jpg","fileCreatedAt":"2024-05-01T12:00:00.000Z","thumbhash":"h1"},
                {"id":"v","type":"VIDEO","originalFileName":"v.mp4"},
                {"id":"b","type":"IMAGE"},
                {"id":"c","type":"IMAGE","originalFileName":null,"fileCreatedAt":null,"thumbhash":null}]""",
        )
        assertEquals(
            listOf(
                ImmichShareSource.ImmichAsset("a", "a.jpg", millis("2024-05-01T12:00:00Z"), "h1"),
                ImmichShareSource.ImmichAsset("b", "b", null),
                // Explicit nulls are absent values, not the text "null" (as Android's org.json read them).
                ImmichShareSource.ImmichAsset("c", "c", null),
            ),
            ImmichShareSource.parseAssets(assets),
        )
    }

    @Test
    fun `immich capture time out of range reads as absent`() {
        val assets = WireJson.decodeFromString<List<ImmichShareSource.Asset>>(
            """[{"id":"a","type":"IMAGE","fileCreatedAt":"+999999999-01-01T00:00:00Z"},
                {"id":"b","type":"IMAGE","fileCreatedAt":"-999999999-01-01T00:00:00"}]""",
        )
        assertEquals(listOf(null, null), ImmichShareSource.parseAssets(assets).map { it.takenAt })
    }

    @Test
    fun `immich timeline bucket keeps live images only, named and dated by capture time`() {
        val bucket = WireJson.decodeFromString<ImmichShareSource.TimelineBucket>(
            """{"id":["a","v","t","b"],
                "isImage":[true,false,true,true],
                "isTrashed":[false,false,true,false],
                "fileCreatedAt":["2026-10-13T09:58:52.749",null,"2026-10-01T00:00:00","x"],
                "thumbhash":["h1",null,"h3",null]}""",
        )
        assertEquals(
            listOf(
                ImmichShareSource.ImmichAsset("a", "2026-10-13 09:58", millis("2026-10-13T09:58:52.749Z"), "h1"),
                ImmichShareSource.ImmichAsset("b", "b", null),
            ),
            ImmichShareSource.parseTimelineBucket(bucket),
        )
    }

    @Test
    fun `source store reads what earlier versions saved and writes the same format back`() {
        val camera = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
        val sources = listOf(
            SourceConfig.ImmichShare("2", "Im", "https://p/share/k"),
            SourceConfig.LocalFolder("3", "Camera", camera),
        )
        // The Immich entry byte for byte as the org.json encoder wrote it on a device (Android escapes `/`).
        val saved = """[{"type":"immich","id":"2","label":"Im","url":"https:\/\/p\/share\/k"},""" +
            """{"type":"folder","id":"3","label":"Camera",""" +
            """"uri":"content:\/\/com.android.externalstorage.documents\/tree\/primary%3ADCIM"}]"""
        assertEquals(sources, SourceStore.decode(saved))
        assertEquals(Json.parseToJsonElement(saved), Json.parseToJsonElement(SourceStore.encode(sources)))
    }

    @Test
    fun `source store survives unreadable data`() {
        assertEquals(emptyList<SourceConfig>(), SourceStore.decode("not json"))
        assertEquals(emptyList<SourceConfig>(), SourceStore.decode("""{"id": "1"}"""))
    }

    @Test
    fun `source store drops malformed, unknown and no longer supported entries and keeps the rest`() {
        val json = """
            [
              {"type": "immich", "id": "1", "label": "Im", "url": "https://p/share/k"},
              {"type": "urllist", "id": "2", "label": "List", "url": "https://e/l.txt"},
              42,
              {"type": "smugmug", "id": "3", "label": "Smug", "url": "https://s/g"},
              {"id": "5", "label": "Untyped", "url": "https://u/x"},
              {"type": "folder", "id": "6", "label": "No URI"},
              {"type": "nextcloud", "id": "4", "label": "NC", "url": "https://c/s/t", "password": "pw"},
              {"type": "folder", "id": "7", "label": "Pics", "uri": "content://p/tree/x"}
            ]
        """.trimIndent()
        assertEquals(
            listOf(
                SourceConfig.ImmichShare("1", "Im", "https://p/share/k"),
                SourceConfig.LocalFolder("7", "Pics", "content://p/tree/x"),
            ),
            SourceStore.decode(json),
        )
    }

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()
}
