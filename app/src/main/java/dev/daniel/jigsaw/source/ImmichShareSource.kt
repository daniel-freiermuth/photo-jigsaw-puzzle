package dev.daniel.jigsaw.source

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Lists images in an Immich shared link (album or individual-assets share).
 *
 * Uses `GET /api/shared-links/me`; for album shares the assets come from `GET /api/albums/{id}` on
 * older servers, or the timeline API (`/api/timeline/buckets` + `/api/timeline/bucket`) on servers
 * whose album response no longer embeds assets. Every request carries the share `key` (or
 * custom-URL `slug`) query parameter instead of a user session.
 */
class ImmichShareSource(
    shareUrl: String,
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageSource {

    private val share = parseShareUrl(shareUrl)

    override suspend fun listImages(): List<RemoteImage> = withContext(ioDispatcher) {
        val assets = try {
            val link = JSONObject(get(api("shared-links/me")))
            val direct = link.optJSONArray("assets") ?: JSONArray()
            val albumId = link.optJSONObject("album")?.getString("id")
            if (direct.length() == 0 && albumId != null) albumAssets(albumId) else parseAssets(direct)
        } catch (e: JSONException) {
            throw UnexpectedResponseException("Unexpected response from Immich", e)
        }
        assets.map { asset ->
            RemoteImage(
                id = asset.id,
                name = asset.name,
                thumbnail = HttpResource(api("assets/${asset.id}/thumbnail").withQuery("size", "thumbnail")),
                // "preview" is a server-rendered, EXIF-rotated JPEG (~1440px): ideal puzzle resolution.
                full = HttpResource(api("assets/${asset.id}/thumbnail").withQuery("size", "preview")),
                takenAt = asset.takenAt,
            )
        }
    }

    private fun get(url: String) = String(Http.getBytes(HttpResource(url), client), Charsets.UTF_8)

    private fun albumAssets(albumId: String): List<ImmichAsset> {
        val album = JSONObject(get(api("albums/$albumId")))
        album.optJSONArray("assets")?.let { return parseAssets(it) }
        val buckets = JSONArray(get(api("timeline/buckets").withQuery("albumId", albumId)))
        return (0 until buckets.length()).flatMap { i ->
            val bucket = buckets.getJSONObject(i).getString("timeBucket")
            parseTimelineBucket(
                JSONObject(
                    get(api("timeline/bucket").withQuery("albumId", albumId).withQuery("timeBucket", bucket)),
                ),
            )
        }
    }

    private fun api(path: String): String = share.base.newBuilder()
        .addPathSegments("api/$path")
        .addQueryParameter(share.authParam, share.authValue)
        .build()
        .toString()

    private fun String.withQuery(name: String, value: String) =
        toHttpUrl().newBuilder().addQueryParameter(name, value).build().toString()

    /** [authParam] is `key` for `/share/<key>` links and `slug` for custom `/s/<slug>` links. */
    data class Share(val base: HttpUrl, val authParam: String, val authValue: String)

    /** An image in the share; [takenAt] is the capture time in epoch milliseconds, if known. */
    data class ImmichAsset(val id: String, val name: String, val takenAt: Long?)

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

        /** Every image asset; videos and other types are skipped. */
        fun parseAssets(assets: JSONArray): List<ImmichAsset> =
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .filter { it.optString("type") == "IMAGE" }
                .map {
                    ImmichAsset(
                        id = it.getString("id"),
                        name = it.optString("originalFileName", it.getString("id")),
                        takenAt = parseIsoTimestamp(it.optString("fileCreatedAt").ifEmpty { null }),
                    )
                }

        /**
         * Timeline buckets are columnar: parallel arrays per field. Returns every non-trashed image,
         * named by capture time since filenames aren't part of this payload.
         */
        fun parseTimelineBucket(bucket: JSONObject): List<ImmichAsset> {
            val ids = bucket.optJSONArray("id") ?: return emptyList()
            val isImage = bucket.optJSONArray("isImage")
            val isTrashed = bucket.optJSONArray("isTrashed")
            val created = bucket.optJSONArray("fileCreatedAt")
            return (0 until ids.length())
                .filter { isImage?.optBoolean(it, false) == true && isTrashed?.optBoolean(it, false) != true }
                .map { i ->
                    val id = ids.getString(i)
                    val createdAt = created?.optString(i)?.takeUnless { created.isNull(i) }
                    val name = createdAt
                        ?.takeIf { it.length >= TIMESTAMP_TO_MINUTE }
                        ?.take(TIMESTAMP_TO_MINUTE)
                        ?.replace('T', ' ')
                        ?: id
                    ImmichAsset(id, name, parseIsoTimestamp(createdAt))
                }
        }

        /** Length of an ISO timestamp up to the minute: `YYYY-MM-DDTHH:MM`. */
        private const val TIMESTAMP_TO_MINUTE = 16
    }
}
