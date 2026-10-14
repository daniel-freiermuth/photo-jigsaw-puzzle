package dev.daniel.jigsaw.source

import android.util.Xml
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection.HTTP_BAD_METHOD
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.URLDecoder

/**
 * Lists images in a Nextcloud public share (recursively) over the public WebDAV endpoint.
 *
 * Tries the legacy `public.php/webdav` endpoint first (works on all versions, supports passwords),
 * then the newer `public.php/dav/files/<token>` endpoint.
 */
class NextcloudShareSource(
    shareUrl: String,
    private val password: String,
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageSource {

    private val share = parseShareUrl(shareUrl)

    override suspend fun listImages(): List<RemoteImage> = withContext(ioDispatcher) {
        var lastError: IOException? = null
        for (endpoint in endpoints()) {
            try {
                return@withContext crawl(endpoint)
            } catch (e: HttpStatusException) {
                // Not found / method not allowed: this server lacks the endpoint. Anything else is a real failure.
                if (e.code != HTTP_NOT_FOUND && e.code != HTTP_BAD_METHOD) throw e
                lastError = e
            }
        }
        throw lastError ?: IOException("No WebDAV endpoint available")
    }

    private data class Endpoint(val root: HttpUrl, val headers: Map<String, String>)

    private fun endpoints(): List<Endpoint> {
        val base = share.base
        val common = mapOf("X-Requested-With" to "XMLHttpRequest")
        val legacy = Endpoint(
            root = base.newBuilder().addPathSegments("public.php/webdav/").build(),
            headers = common + ("Authorization" to Credentials.basic(share.token, password)),
        )
        val modern = Endpoint(
            root = base.newBuilder().addPathSegments("public.php/dav/files/${share.token}/").build(),
            headers = if (password.isEmpty()) {
                common
            } else {
                common + ("Authorization" to Credentials.basic("anonymous", password))
            },
        )
        return listOf(legacy, modern)
    }

    private fun crawl(endpoint: Endpoint): List<RemoteImage> {
        val rootPath = endpoint.root.encodedPath.decodeUrlPath()
        val pending = ArrayDeque(listOf(endpoint.root))
        val images = mutableListOf<RemoteImage>()
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_FOLDERS) {
            val dir = pending.removeFirst()
            visited++
            val dirPath = dir.encodedPath.decodeUrlPath().trimEnd('/')
            val children = propfind(dir, endpoint.headers)
                .mapNotNull { entry -> dir.resolve(entry.href)?.let { entry to it } }
                .filter { (_, url) -> url.encodedPath.decodeUrlPath().trimEnd('/') != dirPath }
            children.filter { (entry, _) -> entry.isCollection }.forEach { (_, url) -> pending.addLast(url) }
            images += children
                .filter { (entry, _) -> !entry.isCollection && entry.contentType.isPuzzleImage() }
                .map { (entry, url) -> image(url, rootPath, endpoint.headers, entry) }
        }
        return images.sortedBy { it.id.lowercase() }
    }

    private fun image(url: HttpUrl, rootPath: String, headers: Map<String, String>, entry: DavEntry): RemoteImage {
        val relative = "/" + url.encodedPath.decodeUrlPath().removePrefix(rootPath).trimStart('/')
        val full = HttpResource(url.toString(), headers, revision = entry.etag)
        return RemoteImage(
            id = relative,
            name = relative.substringAfterLast('/'),
            thumbnail = if (password.isEmpty()) previewResource(relative, entry.etag) else full,
            full = full,
            takenAt = entry.takenAt,
        )
    }

    private fun previewResource(relativePath: String, etag: String?) = HttpResource(
        url = share.base.newBuilder()
            .addPathSegments("index.php/apps/files_sharing/publicpreview")
            .addPathSegment(share.token)
            .addQueryParameter("file", relativePath)
            .addQueryParameter("x", THUMBNAIL_SIZE)
            .addQueryParameter("y", THUMBNAIL_SIZE)
            .addQueryParameter("a", "1")
            .build()
            .toString(),
        revision = etag,
    )

    private fun propfind(dir: HttpUrl, headers: Map<String, String>): List<DavEntry> {
        val request = Request.Builder()
            .url(dir)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, dir.toString())
            val body = response.body ?: throw UnexpectedResponseException("Empty PROPFIND response")
            return parseResponse(body.charStream(), dir)
        }
    }

    private fun parseResponse(reader: Reader, dir: HttpUrl): List<DavEntry> = try {
        parseMultistatus(reader, Xml.newPullParser())
    } catch (e: XmlPullParserException) {
        throw UnexpectedResponseException("Malformed WebDAV response from $dir", e)
    }

    data class Share(val base: HttpUrl, val token: String)

    /** One PROPFIND result; [takenAt] is the photo's capture time if Nextcloud knows it, else its modification time. */
    data class DavEntry(
        val href: String,
        val isCollection: Boolean,
        val contentType: String,
        val takenAt: Long? = null,
        /** Changes whenever the file's content does; versions cached downloads. */
        val etag: String? = null,
    )

    /** Properties of the `<d:response>` being read. */
    private class EntryBuilder {
        var href: String? = null
        var isCollection = false
        var contentType = ""
        var modified: Long? = null
        var captured: Long? = null
        var etag: String? = null

        fun build() = href?.let { DavEntry(it, isCollection, contentType, captured ?: modified, etag) }
    }

    companion object {
        private const val MAX_FOLDERS = 200
        private const val THUMBNAIL_SIZE = "400"
        private const val DAV = "DAV:"
        private const val NEXTCLOUD = "http://nextcloud.org/ns"

        // nc:metadata-photos-original_date_time is the EXIF capture time (Nextcloud 28+); older
        // servers just omit it and getlastmodified is used instead.
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:nc="http://nextcloud.org/ns">
              <d:prop>
                <d:resourcetype/><d:getcontenttype/><d:getlastmodified/><d:getetag/>
                <nc:metadata-photos-original_date_time/>
              </d:prop>
            </d:propfind>
        """.trimIndent()

        /** `https://host[/sub]/[index.php/]s/<token>[/...]` → base `https://host[/sub]/` + token. */
        fun parseShareUrl(url: String): Share {
            val parsed = url.trim().toHttpUrl()
            val segments = parsed.pathSegments
            val sIndex = segments.indexOf("s")
            require(sIndex >= 0 && sIndex + 1 < segments.size && segments[sIndex + 1].isNotEmpty()) {
                "Not a Nextcloud share link (expected …/s/<token>): $url"
            }
            val baseSegments = segments.subList(0, sIndex).let {
                if (it.lastOrNull() == "index.php") it.dropLast(1) else it
            }
            val base = parsed.newBuilder()
                .query(null)
                .fragment(null)
                .encodedPath("/")
                .apply { baseSegments.filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }
                .addPathSegment("") // trailing slash so path appends work uniformly
                .build()
            return Share(base, segments[sIndex + 1])
        }

        fun parseMultistatus(reader: Reader, parser: XmlPullParser): List<DavEntry> {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(reader)
            val entries = mutableListOf<DavEntry>()
            var current = EntryBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val isResponse = parser.namespace == DAV && parser.name == "response"
                when {
                    event == XmlPullParser.START_TAG && isResponse -> current = EntryBuilder()
                    event == XmlPullParser.START_TAG -> readProperty(parser, current)
                    event == XmlPullParser.END_TAG && isResponse -> current.build()?.let { entries += it }
                }
                event = parser.next()
            }
            return entries
        }

        private fun readProperty(parser: XmlPullParser, entry: EntryBuilder) {
            when (parser.namespace to parser.name) {
                DAV to "href" -> entry.href = parser.nextText().trim()
                DAV to "collection" -> entry.isCollection = true
                DAV to "getcontenttype" -> entry.contentType = parser.nextText().trim()
                DAV to "getlastmodified" -> entry.modified = parseHttpDate(parser.nextText().trim())
                DAV to "getetag" -> entry.etag = parser.nextText().trim().ifEmpty { null }
                NEXTCLOUD to "metadata-photos-original_date_time" ->
                    entry.captured = parseEpochSeconds(parser.nextText())
            }
        }
    }
}

internal fun String.isPuzzleImage(): Boolean {
    val type = substringBefore(';').trim().lowercase()
    return type.startsWith("image/") && type != "image/svg+xml"
}

internal fun String.decodeUrlPath(): String = URLDecoder.decode(replace("+", "%2B"), "UTF-8")
