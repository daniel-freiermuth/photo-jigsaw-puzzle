package dev.daniel.jigsaw.source

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UrlListSourceTest {

    private val server = MockWebServer()

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    private fun TestScope.source(manifestPath: String) =
        UrlListSource(server.url(manifestPath).toString(), OkHttpClient(), StandardTestDispatcher(testScheduler))

    private fun serve(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    /** The picture the source should report for [url]: fetched as-is for both thumbnail and puzzle. */
    private fun image(url: String, name: String) =
        RemoteImage(id = url, name = name, thumbnail = HttpResource(url), full = HttpResource(url))

    @Test
    fun `a text manifest lists each entry once, resolved against the manifest's location`() = runTest {
        serve("# holiday\n a.jpg\n\nsub/b%20c.png\n/root.webp\nhttps://cdn.example.com/x/d.jpg\na.jpg\n")

        assertEquals(
            listOf(
                image(server.url("/pics/a.jpg").toString(), "a.jpg"),
                image(server.url("/pics/sub/b%20c.png").toString(), "b c.png"),
                image(server.url("/root.webp").toString(), "root.webp"),
                image("https://cdn.example.com/x/d.jpg", "d.jpg"),
            ),
            source("/pics/list.txt").listImages(),
        )
        assertEquals("/pics/list.txt", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a JSON manifest names each picture after its last path segment, or its URL when it has none`() = runTest {
        serve("""["https://cdn.example.com/album/", "https://cdn.example.com/"]""")

        assertEquals(
            listOf(
                image("https://cdn.example.com/album/", "album"),
                image("https://cdn.example.com/", "https://cdn.example.com/"),
            ),
            source("/list.json").listImages(),
        )
    }

    @Test
    fun `a manifest that looks like JSON but isn't is reported as an unexpected response`() = runTest {
        serve("""["a.jpg", """)

        val failure = runCatching { source("/list.json").listImages() }.exceptionOrNull()

        assertInstanceOf(UnexpectedResponseException::class.java, failure)
    }

    @Test
    fun `a manifest the server refuses fails with its status`() = runTest {
        serve("gone", code = 404)

        val failure = runCatching { source("/list.txt").listImages() }.exceptionOrNull()

        assertEquals(404, assertInstanceOf(HttpStatusException::class.java, failure).code)
    }
}
