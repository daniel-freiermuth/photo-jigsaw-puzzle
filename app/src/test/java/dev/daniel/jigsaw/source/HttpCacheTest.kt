package dev.daniel.jigsaw.source

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class HttpCacheTest {
    @TempDir
    lateinit var tmp: File

    private val server = MockWebServer()

    private val client: OkHttpClient by lazy { Http.withDiskCache(OkHttpClient(), tmp) }

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    /** A response the server says must not be cached. */
    private fun uncacheable(body: String) =
        MockResponse.Builder().body(body).setHeader("Cache-Control", "no-store").build()

    @Test
    fun `a revisioned picture is kept despite no-store and served when the server is gone`() {
        server.enqueue(uncacheable("v1"))
        val url = server.url("/thumb").toString()
        assertEquals("v1", String(Http.getImage(HttpResource(url, revision = "r1"), client)))
        server.close()
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
        assertEquals("/thumb", server.takeRequest().target)
    }

    @Test
    fun `listings always ask the server even when a cached copy is allowed`() {
        server.enqueue(MockResponse.Builder().body("old").setHeader("Cache-Control", "max-age=3600").build())
        server.enqueue(MockResponse.Builder().body("new").setHeader("Cache-Control", "max-age=3600").build())
        val list = HttpResource(server.url("/list.txt").toString())
        assertEquals("old", String(Http.getBytes(list, client)))
        assertEquals("new", String(Http.getBytes(list, client)))
    }

    @Test
    fun `offline, a picture never cached reports the network error`() {
        val url = server.url("/never").toString()
        server.close()
        assertThrows<IOException> { Http.getImage(HttpResource(url, revision = "r"), client) }
    }

    @Test
    fun `server errors are not hidden by the cache`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        val url = server.url("/missing").toString()
        val error = assertThrows<HttpStatusException> {
            Http.getImage(HttpResource(url, revision = "r"), client)
        }
        assertEquals(404, error.code)
    }
}
