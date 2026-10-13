package dev.daniel.jigsaw.source

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

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
    fun `webdav multistatus distinguishes folders and content types`() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response><d:href>/public.php/webdav/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/public.php/webdav/Holiday%202024/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/public.php/webdav/beach.jpg</d:href>
                <d:propstat><d:prop><d:resourcetype/><d:getcontenttype>image/jpeg</d:getcontenttype></d:prop></d:propstat></d:response>
              <d:response><d:href>/public.php/webdav/notes.txt</d:href>
                <d:propstat><d:prop><d:resourcetype/><d:getcontenttype>text/plain</d:getcontenttype></d:prop></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()
        val entries = NextcloudShareSource.parseMultistatus(StringReader(xml), KXmlParser())
        assertEquals(
            listOf(
                NextcloudShareSource.DavEntry("/public.php/webdav/", true, ""),
                NextcloudShareSource.DavEntry("/public.php/webdav/Holiday%202024/", true, ""),
                NextcloudShareSource.DavEntry("/public.php/webdav/beach.jpg", false, "image/jpeg"),
                NextcloudShareSource.DavEntry("/public.php/webdav/notes.txt", false, "text/plain"),
            ),
            entries,
        )
        assertEquals(listOf(false, true, false), listOf("text/plain", "image/png; q=1", "image/svg+xml").map { it.isPuzzleImage() })
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
    fun `immich assets keep images only`() {
        val assets = JSONArray(
            """[{"id":"a","type":"IMAGE","originalFileName":"a.jpg"},
                {"id":"v","type":"VIDEO","originalFileName":"v.mp4"},
                {"id":"b","type":"IMAGE"}]"""
        )
        assertEquals(listOf("a" to "a.jpg", "b" to "b"), ImmichShareSource.parseAssets(assets))
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
    fun `immich timeline bucket keeps live images only, named by capture time`() {
        val bucket = JSONObject(
            """{"id":["a","v","t","b"],
                "isImage":[true,false,true,true],
                "isTrashed":[false,false,true,false],
                "fileCreatedAt":["2026-10-13T09:58:52.749",null,"2026-10-01T00:00:00","x"]}"""
        )
        assertEquals(
            listOf("a" to "2026-10-13 09:58", "b" to "b"),
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
}
