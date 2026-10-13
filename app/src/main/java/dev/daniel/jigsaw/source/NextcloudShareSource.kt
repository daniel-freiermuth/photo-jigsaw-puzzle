package dev.daniel.jigsaw.source

import android.util.Xml
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
import java.io.IOException
import java.io.Reader

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
) : ImageSource {

    private val share = parseShareUrl(shareUrl)

    override suspend fun listImages(): List<RemoteImage> = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        for (endpoint in endpoints()) {
            try {
                return@withContext crawl(endpoint)
            } catch (e: HttpStatusException) {
                // 404/405 means this server lacks the endpoint; anything else is a real failure.
                if (e.code != 404 && e.code != 405) throw e
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
            headers = if (password.isEmpty()) common
            else common + ("Authorization" to Credentials.basic("anonymous", password)),
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
            val entries = propfind(dir, endpoint.headers)
            val dirPath = dir.encodedPath.decodeUrlPath().trimEnd('/')
            for (entry in entries) {
                val entryUrl = dir.resolve(entry.href) ?: continue
                val decodedPath = entryUrl.encodedPath.decodeUrlPath()
                if (decodedPath.trimEnd('/') == dirPath) continue
                if (entry.isCollection) {
                    pending.addLast(entryUrl)
                } else if (entry.contentType.isPuzzleImage()) {
                    val relative = "/" + decodedPath.removePrefix(rootPath).trimStart('/')
                    val full = HttpResource(entryUrl.toString(), endpoint.headers)
                    images += RemoteImage(
                        id = relative,
                        name = relative.substringAfterLast('/'),
                        thumbnail = if (password.isEmpty()) previewResource(relative) else full,
                        full = full,
                    )
                }
            }
        }
        return images.sortedBy { it.id.lowercase() }
    }

    private fun previewResource(relativePath: String) = HttpResource(
        share.base.newBuilder()
            .addPathSegments("index.php/apps/files_sharing/publicpreview")
            .addPathSegment(share.token)
            .addQueryParameter("file", relativePath)
            .addQueryParameter("x", "400")
            .addQueryParameter("y", "400")
            .addQueryParameter("a", "1")
            .build()
            .toString()
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
            val body = response.body ?: throw IOException("Empty PROPFIND response")
            return parseMultistatus(body.charStream(), Xml.newPullParser())
        }
    }

    class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for $url")

    data class Share(val base: HttpUrl, val token: String)

    data class DavEntry(val href: String, val isCollection: Boolean, val contentType: String)

    companion object {
        private const val MAX_FOLDERS = 200
        private const val DAV = "DAV:"
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop><d:resourcetype/><d:getcontenttype/></d:prop>
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
            var href: String? = null
            var isCollection = false
            var contentType = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.namespace == DAV) {
                    when (parser.name) {
                        "response" -> { href = null; isCollection = false; contentType = "" }
                        "href" -> href = parser.nextText().trim()
                        "collection" -> isCollection = true
                        "getcontenttype" -> contentType = parser.nextText().trim()
                    }
                } else if (event == XmlPullParser.END_TAG && parser.namespace == DAV && parser.name == "response") {
                    href?.let { entries += DavEntry(it, isCollection, contentType) }
                }
                event = parser.next()
            }
            return entries
        }
    }
}

internal fun String.isPuzzleImage(): Boolean {
    val type = substringBefore(';').trim().lowercase()
    return type.startsWith("image/") && type != "image/svg+xml"
}

private fun String.decodeUrlPath(): String = java.net.URLDecoder.decode(replace("+", "%2B"), "UTF-8")
