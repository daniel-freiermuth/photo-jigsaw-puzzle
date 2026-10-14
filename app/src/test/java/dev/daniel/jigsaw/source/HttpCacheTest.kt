package dev.daniel.jigsaw.source

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class HttpCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()

    private val client: OkHttpClient by lazy { Http.withDiskCache(OkHttpClient(), tmp.newFolder("http")) }

    @After
    fun stop() = server.shutdown()

    /** A response the server says must not be cached. */
    private fun uncacheable(body: String) = MockResponse().setBody(body).setHeader("Cache-Control", "no-store")

    @Test
    fun `a revisioned picture is kept despite no-store and served when the server is gone`() {
        server.enqueue(uncacheable("v1"))
        val url = server.url("/thumb").toString()
        assertEquals("v1", String(Http.getImage(HttpResource(url, revision = "r1"), client)))
        server.shutdown()
        assertEquals("v1", String(Http.getImage(HttpResource(url, revision = "r1"), client)))
    }

    @Test
    fun `a new revision of the same url is downloaded again`() {
        server.enqueue(uncacheable("v1"))
        server.enqueue(uncacheable("v2"))
        val url = server.url("/thumb").toString()
        assertEquals("v1", String(Http.getImage(HttpResource(url, revision = "r1"), client)))
        assertEquals("v2", String(Http.getImage(HttpResource(url, revision = "r2"), client)))
        assertEquals("v1", String(Http.getImage(HttpResource(url, revision = "r1"), client)))
        assertEquals(2, server.requestCount)
        assertEquals("/thumb", server.takeRequest().path)
    }

    @Test
    fun `listings always ask the server even when a cached copy is allowed`() {
        server.enqueue(MockResponse().setBody("old").setHeader("Cache-Control", "max-age=3600"))
        server.enqueue(MockResponse().setBody("new").setHeader("Cache-Control", "max-age=3600"))
        val list = HttpResource(server.url("/list.txt").toString())
        assertEquals("old", String(Http.getBytes(list, client)))
        assertEquals("new", String(Http.getBytes(list, client)))
    }

    @Test
    fun `offline, a picture never cached reports the network error`() {
        val url = server.url("/never").toString()
        server.shutdown()
        assertThrows(IOException::class.java) { Http.getImage(HttpResource(url, revision = "r"), client) }
    }

    @Test
    fun `server errors are not hidden by the cache`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val url = server.url("/missing").toString()
        val error = assertThrows(HttpStatusException::class.java) {
            Http.getImage(HttpResource(url, revision = "r"), client)
        }
        assertEquals(404, error.code)
    }
}
