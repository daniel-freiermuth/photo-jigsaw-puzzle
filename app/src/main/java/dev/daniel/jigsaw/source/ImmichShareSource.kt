package dev.daniel.jigsaw.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lists images in an Immich shared link (album or individual-assets share).
 *
 * Uses `GET /api/shared-links/me` and, for album shares, `GET /api/albums/{id}`; every request carries
 * the share `key` (or custom-URL `slug`) query parameter instead of a user session.
 */
class ImmichShareSource(
    shareUrl: String,
    private val client: OkHttpClient,
) : ImageSource {

    private val share = parseShareUrl(shareUrl)

    override suspend fun listImages(): List<RemoteImage> = withContext(Dispatchers.IO) {
        val link = JSONObject(get(api("shared-links/me")))
        var assets = link.optJSONArray("assets") ?: JSONArray()
        val album = link.optJSONObject("album")
        if (assets.length() == 0 && album != null) {
            assets = JSONObject(get(api("albums/${album.getString("id")}"))).optJSONArray("assets") ?: JSONArray()
        }
        parseAssets(assets).map { (id, name) ->
            RemoteImage(
                id = id,
                name = name,
                thumbnail = HttpResource(api("assets/$id/thumbnail").withQuery("size", "thumbnail")),
                // "preview" is a server-rendered, EXIF-rotated JPEG (~1440px): ideal puzzle resolution.
                full = HttpResource(api("assets/$id/thumbnail").withQuery("size", "preview")),
            )
        }
    }

    private fun get(url: String) = String(Http.getBytes(HttpResource(url), client), Charsets.UTF_8)

    private fun api(path: String): String = share.base.newBuilder()
        .addPathSegments("api/$path")
        .addQueryParameter(share.authParam, share.authValue)
        .build()
        .toString()

    private fun String.withQuery(name: String, value: String) =
        toHttpUrl().newBuilder().addQueryParameter(name, value).build().toString()

    /** [authParam] is `key` for `/share/<key>` links and `slug` for custom `/s/<slug>` links. */
    data class Share(val base: HttpUrl, val authParam: String, val authValue: String)

    companion object {
        fun parseShareUrl(url: String): Share {
            val parsed = url.trim().toHttpUrl()
            val segments = parsed.pathSegments
            val index = segments.indexOfFirst { it == "share" || it == "s" }
            require(index >= 0 && index + 1 < segments.size && segments[index + 1].isNotEmpty()) {
                "Not an Immich shared link (expected …/share/<key> or …/s/<slug>): $url"
            }
            val base = parsed.newBuilder()
                .query(null)
                .fragment(null)
                .encodedPath("/")
                .apply { segments.subList(0, index).filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }
                .addPathSegment("")
                .build()
            val param = if (segments[index] == "share") "key" else "slug"
            return Share(base, param, segments[index + 1])
        }

        /** Returns (id, filename) for every image asset; videos and other types are skipped. */
        fun parseAssets(assets: JSONArray): List<Pair<String, String>> =
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .filter { it.optString("type") == "IMAGE" }
                .map { it.getString("id") to it.optString("originalFileName", it.getString("id")) }
    }
}
