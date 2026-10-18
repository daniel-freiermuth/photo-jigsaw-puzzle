package dev.daniel.jigsaw.source

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kxml2.io.KXmlParser

class NextcloudShareSourceTest {

    private val server = MockWebServer()

    private val client = OkHttpClient()

    /** Encoded request path → answer; anything else is answered by [fallback]. */
    private val routes = mutableMapOf<String, MockResponse>()

    private var fallback: (String) -> MockResponse = { status(404) }

    @BeforeEach
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return routes[path] ?: fallback(path)
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `the modern and the legacy endpoint give every picture the same id`() {
        serveTree(MODERN)
        val modern = list()
        assertEquals(3, server.requestCount)

        routes.clear()
        serveTree(LEGACY)
        val legacy = list()

        assertEquals(TREE_IDS, modern.map { it.id })
        assertEquals(TREE_IDS, legacy.map { it.id })
        assertEquals(listOf("a.png", "B.jpg", "c.jpg", "d.webp"), modern.map { it.name })
        assertEquals(server.url("${MODERN}sub/Deep%20Folder/d.webp").toString(), modern.last().full.url)
        assertEquals(server.url("${LEGACY}sub/Deep%20Folder/d.webp").toString(), legacy.last().full.url)
    }

    @Test
    fun `a server older than Nextcloud 29 is listed through the legacy endpoint`() {
        for (code in listOf(404, 405)) {
            routes.clear()
            routes[MODERN] = status(code)
            serveTree(LEGACY)
            assertEquals(TREE_IDS, list().map { it.id }, "modern answering $code")
        }
    }

    @Test
    fun `a wrong password or a broken server fails without trying the legacy endpoint`() {
        serveTree(LEGACY)
        for (code in listOf(401, 500)) {
            routes[MODERN] = status(code)
            val before = server.requestCount
            assertEquals(code, assertThrows<HttpStatusException> { list() }.code)
            assertEquals(before + 1, server.requestCount, "modern answering $code")
        }
    }

    @Test
    fun `when neither endpoint exists the last status is reported`() {
        routes[MODERN] = status(405)
        assertEquals(404, assertThrows<HttpStatusException> { list() }.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a share with more than 200 folders is refused rather than listed in part`() {
        serveFolderChain(depth = 201)
        assertEquals(200, assertThrows<TooManyFoldersException> { list() }.limit)
        // The modern endpoint answered, so the legacy one is not tried.
        assertEquals(200, server.requestCount)
    }

    @Test
    fun `a share with exactly 200 folders is listed in full`() {
        serveFolderChain(depth = 200)
        assertEquals(200, list().size)
        assertEquals(200, server.requestCount)
    }

    @Test
    fun `without a password thumbnails come from the public preview`() {
        serveTree(MODERN)
        val image = list().single { it.id == "/sub/c.jpg" }

        val thumbnail = image.thumbnail.url.toHttpUrl()
        assertEquals("/nextcloud/index.php/apps/files_sharing/publicpreview/tok", thumbnail.encodedPath)
        assertEquals("/sub/c.jpg", thumbnail.queryParameter("file"))
        assertEquals(emptyMap<String, String>(), image.thumbnail.headers)
        assertEquals(ETAG, image.thumbnail.revision)
        assertEquals(server.url("${MODERN}sub/c.jpg").toString(), image.full.url)
        assertEquals(ETAG, image.full.revision)
    }

    @Test
    fun `with a password thumbnails are the full picture, sent with the share's credentials`() {
        serveTree(MODERN)
        val modern = list(PASSWORD).single { it.id == "/sub/c.jpg" }
        assertEquals(modern.full, modern.thumbnail)
        assertEquals(Credentials.basic("anonymous", PASSWORD), modern.full.headers["Authorization"])
        assertEquals(Credentials.basic("anonymous", PASSWORD), server.takeRequest().headers["Authorization"])

        routes.clear()
        serveTree(LEGACY)
        val legacy = list(PASSWORD).single { it.id == "/sub/c.jpg" }
        assertEquals(legacy.full, legacy.thumbnail)
        assertEquals(Credentials.basic("tok", PASSWORD), legacy.full.headers["Authorization"])
    }

    @Test
    fun `a listing with a malformed href fails as an unexpected response`() {
        routes[MODERN] = listing(folder(MODERN), file("${MODERN}100%zz.jpg", "image/jpeg"))
        assertThrows<UnexpectedResponseException> { list() }
    }

    private fun list(password: String = ""): List<RemoteImage> = runBlocking {
        NextcloudShareSource(
            server.url("/nextcloud/index.php/s/tok").toString(),
            password,
            client,
            newParser = ::KXmlParser,
        ).listImages()
    }

    /** Pictures in a root folder and two levels of subfolders, next to a text file and an SVG. */
    private fun serveTree(root: String) {
        routes[root] = listing(
            folder(root),
            folder("${root}sub/"),
            file("${root}B.jpg", "image/jpeg"),
            file("${root}a.png", "image/png"),
            file("${root}notes.txt", "text/plain"),
            file("${root}logo.svg", "image/svg+xml"),
        )
        routes["${root}sub/"] = listing(
            folder("${root}sub/"),
            file("${root}sub/c.jpg", "image/jpeg", ETAG),
            folder("${root}sub/Deep%20Folder/"),
        )
        routes["${root}sub/Deep%20Folder/"] = listing(
            folder("${root}sub/Deep%20Folder/"),
            file("${root}sub/Deep%20Folder/d.webp", "image/webp"),
        )
    }

    /** [depth] nested folders on the modern endpoint, each holding one picture and the next folder. */
    private fun serveFolderChain(depth: Int) {
        fallback = { path ->
            val level = path.removePrefix(MODERN).split("deeper/").size
            when {
                !path.startsWith(MODERN) -> status(404)
                level < depth -> listing(folder(path), folder("${path}deeper/"), file("${path}p.jpg", "image/jpeg"))
                else -> listing(folder(path), file("${path}p.jpg", "image/jpeg"))
            }
        }
    }

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    private fun listing(vararg responses: String) = MockResponse.Builder()
        .code(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .body("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">${responses.joinToString("")}</d:multistatus>""")
        .build()

    private fun folder(href: String) =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop>" +
            "<d:resourcetype><d:collection/></d:resourcetype>" +
            "</d:prop></d:propstat></d:response>"

    private fun file(href: String, type: String, etag: String? = null) =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop>" +
            "<d:resourcetype/><d:getcontenttype>$type</d:getcontenttype>" +
            etag?.let { "<d:getetag>$it</d:getetag>" }.orEmpty() +
            "</d:prop></d:propstat></d:response>"

    private companion object {
        const val LEGACY = "/nextcloud/public.php/webdav/"
        const val MODERN = "/nextcloud/public.php/dav/files/tok/"
        const val PASSWORD = "secret"
        const val ETAG = "\"e1\""

        /** Sorted case-insensitively, so `a.png` comes before `B.jpg`. */
        val TREE_IDS = listOf("/a.png", "/B.jpg", "/sub/c.jpg", "/sub/Deep Folder/d.webp")
    }
}
