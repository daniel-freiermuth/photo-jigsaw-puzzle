package dev.daniel.jigsaw.source

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant

class ImmichShareSourceTest {

    private val server = MockWebServer()

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    /** Answers each request with [body] for its path and query, or 404 when that returns null. */
    private fun serve(body: (HttpUrl) -> String?) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                body(request.url)?.let { MockResponse.Builder().body(it).build() }
                    ?: MockResponse.Builder().code(404).build()
        }
    }

    /** Serves fixed bodies by path; the timeline bucket is keyed as `path?timeBucket`. */
    private fun serve(routes: Map<String, String>) = serve { url ->
        routes[url.queryParameter("timeBucket")?.let { "${url.encodedPath}?$it" } ?: url.encodedPath]
    }

    private fun TestScope.source(sharePath: String) =
        ImmichShareSource(server.url(sharePath).toString(), OkHttpClient(), StandardTestDispatcher(testScheduler))

    /** Every request the server saw, in order. */
    private fun requests(): List<HttpUrl> = List(server.requestCount) { server.takeRequest().url }

    /**
     * The picture [listImages][ImmichShareSource.listImages] should report for [id]; [api] is the expected
     * API path and auth query, e.g. `/api?key=k3y`.
     */
    private fun image(
        id: String,
        name: String = id,
        takenAt: Long? = null,
        thumbhash: String? = null,
        api: String = "/api?key=k3y",
    ): RemoteImage {
        val (path, auth) = api.split('?')
        return RemoteImage(
            id = id,
            name = name,
            thumbnail = HttpResource(
                server.url("$path/assets/$id/thumbnail?$auth&size=thumbnail").toString(),
                revision = thumbhash,
            ),
            full = HttpResource(
                server.url("$path/assets/$id/thumbnail?$auth&size=preview").toString(),
                revision = thumbhash,
            ),
            takenAt = takenAt,
        )
    }

    @Test
    fun `an individual-assets share uses its own assets without asking for an album or timeline`() = runTest {
        serve(
            mapOf(
                "/api/shared-links/me" to
                    """{"assets":[
                        {"id":"a","type":"IMAGE","originalFileName":"a.jpg","fileCreatedAt":"2024-05-01T12:00:00.000Z","thumbhash":"h1"},
                        {"id":"v","type":"VIDEO","originalFileName":"v.mp4"}],
                       "album":{"id":"al1"}}""",
            ),
        )
        assertEquals(
            listOf(image("a", "a.jpg", millis("2024-05-01T12:00:00Z"), "h1")),
            source("/share/k3y").listImages(),
        )
        assertEquals(listOf("/api/shared-links/me"), requests().map { it.encodedPath })
    }

    @Test
    fun `an album share on an older server takes the assets embedded in the album`() = runTest {
        serve(
            mapOf(
                "/immich/api/shared-links/me" to """{"assets":[],"album":{"id":"al1"}}""",
                "/immich/api/albums/al1" to
                    """{"id":"al1","assets":[{"id":"b","type":"IMAGE","originalFileName":"b.png"}]}""",
            ),
        )
        assertEquals(
            listOf(image("b", "b.png", api = "/immich/api?slug=summer")),
            source("/immich/s/summer").listImages(),
        )
        val requests = requests()
        assertEquals(listOf("/immich/api/shared-links/me", "/immich/api/albums/al1"), requests.map { it.encodedPath })
        assertEquals(listOf("summer", "summer"), requests.map { it.queryParameter("slug") })
    }

    @Test
    fun `an album share on a newer server concatenates every timeline bucket in order`() = runTest {
        serve(
            mapOf(
                "/api/shared-links/me" to """{"assets":[],"album":{"id":"al1"}}""",
                "/api/albums/al1" to """{"id":"al1","assetCount":3}""",
                "/api/timeline/buckets" to
                    """[{"timeBucket":"2026-10-01","count":2},{"timeBucket":"2026-09-01","count":1}]""",
                "/api/timeline/bucket?2026-10-01" to
                    """{"id":["c","d"],"isImage":[true,true],"isTrashed":[false,false],
                        "fileCreatedAt":["2026-10-13T09:58:52.749","2026-10-02T08:00:00"],"thumbhash":["hc",null]}""",
                "/api/timeline/bucket?2026-09-01" to
                    """{"id":["e"],"isImage":[true],"isTrashed":[false],
                        "fileCreatedAt":["2026-09-05T07:30:00"],"thumbhash":["he"]}""",
            ),
        )
        assertEquals(
            listOf(
                image("c", "2026-10-13 09:58", millis("2026-10-13T09:58:52.749Z"), "hc"),
                image("d", "2026-10-02 08:00", millis("2026-10-02T08:00:00Z")),
                image("e", "2026-09-05 07:30", millis("2026-09-05T07:30:00Z"), "he"),
            ),
            source("/share/k3y").listImages(),
        )
        val requests = requests()
        assertEquals(
            listOf(
                "/api/shared-links/me",
                "/api/albums/al1",
                "/api/timeline/buckets",
                "/api/timeline/bucket",
                "/api/timeline/bucket",
            ),
            requests.map { it.encodedPath },
        )
        assertEquals(List(5) { "k3y" }, requests.map { it.queryParameter("key") })
        assertEquals(listOf(null, null, "al1", "al1", "al1"), requests.map { it.queryParameter("albumId") })
        assertEquals(
            listOf(null, null, null, "2026-10-01", "2026-09-01"),
            requests.map { it.queryParameter("timeBucket") },
        )
    }

    @Test
    fun `an album share whose timeline has no buckets is empty`() = runTest {
        serve(
            mapOf(
                "/api/shared-links/me" to """{"assets":[],"album":{"id":"al1"}}""",
                "/api/albums/al1" to """{"id":"al1"}""",
                "/api/timeline/buckets" to "[]",
            ),
        )
        assertEquals(emptyList<RemoteImage>(), source("/share/k3y").listImages())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a malformed answer at any step is reported as an IO failure`() = runTest {
        val malformed = listOf(
            mapOf("/api/shared-links/me" to "<html>login</html>"),
            mapOf(
                "/api/shared-links/me" to """{"assets":[],"album":{"id":"al1"}}""",
                "/api/albums/al1" to """{"id":"al1"}""",
                "/api/timeline/buckets" to """{"error":"not an array"}""",
            ),
        )
        for (routes in malformed) {
            serve(routes)
            val failure = runCatching { source("/share/k3y").listImages() }.exceptionOrNull()
            assertInstanceOf(IOException::class.java, failure, "for $routes")
            assertInstanceOf(UnexpectedResponseException::class.java, failure, "for $routes")
        }
    }

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()
}
