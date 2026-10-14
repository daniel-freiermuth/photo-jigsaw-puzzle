package dev.daniel.jigsaw.source

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader
import java.time.Instant

class SourceParsingTest {

    @Test
    fun `nextcloud share url yields base and token`() {
        with(NextcloudShareSource.parseShareUrl("https://cloud.example.com/s/AbC123")) {
            assertEquals("https://cloud.example.com/", base.toString())
            assertEquals("AbC123", token)
        }
        with(NextcloudShareSource.parseShareUrl("https://ex.org/nextcloud/index.php/s/tok?dir=/x#frag")) {
            assertEquals("https://ex.org/nextcloud/", base.toString())
            assertEquals("tok", token)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NextcloudShareSource.parseShareUrl("https://cloud.example.com/apps/files")
        }
    }

    @Test
    fun `webdav multistatus distinguishes folders and content types, prefers capture time, keeps the etag`() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:nc="http://nextcloud.org/ns">
              <d:response><d:href>/public.php/webdav/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/public.php/webdav/Holiday%202024/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/public.php/webdav/beach.jpg</d:href>
                <d:propstat><d:prop><d:resourcetype/><d:getcontenttype>image/jpeg</d:getcontenttype>
                  <d:getlastmodified>Tue, 13 Oct 2026 19:39:24 GMT</d:getlastmodified>
                  <nc:metadata-photos-original_date_time>1714564800</nc:metadata-photos-original_date_time>
                  <d:getetag>"5f1c0e"</d:getetag>
                </d:prop></d:propstat></d:response>
              <d:response><d:href>/public.php/webdav/notes.txt</d:href>
                <d:propstat><d:prop><d:resourcetype/><d:getcontenttype>text/plain</d:getcontenttype>
                  <d:getlastmodified>Tue, 13 Oct 2026 19:39:24 GMT</d:getlastmodified>
                </d:prop></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()
        val entries = NextcloudShareSource.parseMultistatus(StringReader(xml), KXmlParser())
        assertEquals(
            listOf(
                NextcloudShareSource.DavEntry("/public.php/webdav/", true, ""),
                NextcloudShareSource.DavEntry("/public.php/webdav/Holiday%202024/", true, ""),
                NextcloudShareSource.DavEntry(
                    "/public.php/webdav/beach.jpg",
                    false,
                    "image/jpeg",
                    millis("2024-05-01T12:00:00Z"),
                    "\"5f1c0e\"",
                ),
                NextcloudShareSource.DavEntry(
                    "/public.php/webdav/notes.txt",
                    false,
                    "text/plain",
                    millis("2026-10-13T19:39:24Z"),
                ),
            ),
            entries,
        )
        assertEquals(
            listOf(false, true, false),
            listOf("text/plain", "image/png; q=1", "image/svg+xml").map { it.isPuzzleImage() },
        )
    }

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
        val assets = JSONArray(
            """[{"id":"a","type":"IMAGE","originalFileName":"a.jpg","fileCreatedAt":"2024-05-01T12:00:00.000Z","thumbhash":"h1"},
                {"id":"v","type":"VIDEO","originalFileName":"v.mp4"},
                {"id":"b","type":"IMAGE"}]""",
        )
        assertEquals(
            listOf(
                ImmichShareSource.ImmichAsset("a", "a.jpg", millis("2024-05-01T12:00:00Z"), "h1"),
                ImmichShareSource.ImmichAsset("b", "b", null),
            ),
            ImmichShareSource.parseAssets(assets),
        )
    }

    @Test
    fun `url list resolves relative entries from text or json`() {
        val base = "https://example.com/pics/list.txt"
        assertEquals(
            listOf("https://example.com/pics/a.jpg", "https://cdn.example.com/b.png"),
            UrlListSource.parseManifest(base, "# comment\n a.jpg\n\nhttps://cdn.example.com/b.png\na.jpg\n"),
        )
        assertEquals(
            listOf("https://example.com/img/c.webp"),
            UrlListSource.parseManifest(base, """["/img/c.webp"]"""),
        )
    }

    @Test
    fun `immich timeline bucket keeps live images only, named and dated by capture time`() {
        val bucket = JSONObject(
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
    fun `source store round-trips every source type`() {
        val sources = listOf(
            SourceConfig.NextcloudShare("1", "NC", "https://c/s/t", "pw"),
            SourceConfig.ImmichShare("2", "Im", "https://p/share/k"),
            SourceConfig.UrlList("3", "List", "https://e/l.txt"),
        )
        assertEquals(sources, SourceStore.decode(SourceStore.encode(sources)))
    }

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()
}
