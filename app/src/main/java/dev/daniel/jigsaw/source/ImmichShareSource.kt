package dev.daniel.jigsaw.source

import dev.daniel.jigsaw.WireJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

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
            val link = WireJson.decodeFromString<SharedLink>(get(api("shared-links/me")))
            val direct = link.assets.orEmpty()
            val albumId = link.album?.id
            if (direct.isEmpty() && albumId != null) albumAssets(albumId) else parseAssets(direct)
        } catch (e: SerializationException) {
            throw UnexpectedResponseException("Unexpected response from Immich", e)
        }
        assets.map { asset ->
            RemoteImage(
                id = asset.id,
                name = asset.name,
                thumbnail = HttpResource(
                    api("assets/${asset.id}/thumbnail").withQuery("size", "thumbnail"),
                    revision = asset.thumbhash,
                ),
                // "preview" is a server-rendered, EXIF-rotated JPEG (~1440px): ideal puzzle resolution.
                full = HttpResource(
                    api("assets/${asset.id}/thumbnail").withQuery("size", "preview"),
                    revision = asset.thumbhash,
                ),
                takenAt = asset.takenAt,
            )
        }
    }

    private fun get(url: String) = Http.getText(HttpResource(url), client)

    private fun albumAssets(albumId: String): List<ImmichAsset> {
        WireJson.decodeFromString<Album>(get(api("albums/$albumId"))).assets?.let { return parseAssets(it) }
        val buckets = api("timeline/buckets").withQuery("albumId", albumId)
        return WireJson.decodeFromString<List<TimeBucketKey>>(get(buckets)).flatMap { key ->
            val bucket = api("timeline/bucket").withQuery("albumId", albumId).withQuery("timeBucket", key.timeBucket)
            parseTimelineBucket(WireJson.decodeFromString(get(bucket)))
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

    /**
     * An image in the share; [takenAt] is the capture time in epoch milliseconds, if known. [thumbhash]
     * is a digest of the rendered thumbnail: it changes when the picture is edited, so it versions the cache.
     */
    data class ImmichAsset(val id: String, val name: String, val takenAt: Long?, val thumbhash: String? = null)

    // Immich API responses, reduced to the fields read here. Every field the server may send as null or
    // leave out is nullable, so a JSON null reads as null on the device and in tests alike.

    /** `GET /api/shared-links/me`: the assets of an individual-assets share, or the album of an album share. */
    @Serializable
    private class SharedLink(val assets: List<Asset>? = null, val album: AlbumRef? = null)

    @Serializable
    private class AlbumRef(val id: String)

    /** `GET /api/albums/{id}`; newer servers no longer embed [assets]. */
    @Serializable
    private class Album(val assets: List<Asset>? = null)

    /** One entry of `GET /api/timeline/buckets`. */
    @Serializable
    private class TimeBucketKey(val timeBucket: String)

    /** An `AssetResponseDto`. */
    @Serializable
    internal class Asset(
        val id: String,
        val type: String? = null,
        val originalFileName: String? = null,
        val fileCreatedAt: String? = null,
        val thumbhash: String? = null,
    )

    /** `GET /api/timeline/bucket`: columnar, one parallel list per field. */
    @Serializable
    internal class TimelineBucket(
        val id: List<String>? = null,
        val isImage: List<Boolean?>? = null,
        val isTrashed: List<Boolean?>? = null,
        val fileCreatedAt: List<String?>? = null,
        val thumbhash: List<String?>? = null,
    )

    companion object {
        fun parseShareUrl(url: String): Share {
            val parsed = url.trim().toHttpUrl()
            val segments = parsed.pathSegments
            val index = segments.indexOfFirst { it == "share" || it == "s" }
            require(index >= 0 && index + 1 < segments.size && segments[index + 1].isNotEmpty()) {
                "Not an Immich shared link (expected …/share/<key> or …/s/<slug>): $url"
            }
            val base = parsed.baseWithPath(segments.subList(0, index))
            val param = if (segments[index] == "share") "key" else "slug"
            return Share(base, param, segments[index + 1])
        }

        /** Every image asset; videos and other types are skipped. */
        internal fun parseAssets(assets: List<Asset>): List<ImmichAsset> =
            assets.filter { it.type == "IMAGE" }.map {
                ImmichAsset(
                    id = it.id,
                    name = it.originalFileName ?: it.id,
                    takenAt = parseIsoTimestamp(it.fileCreatedAt),
                    thumbhash = it.thumbhash?.ifEmpty { null },
                )
            }

        /**
         * Timeline buckets are columnar: parallel arrays per field. Returns every non-trashed image,
         * named by capture time since filenames aren't part of this payload.
         */
        internal fun parseTimelineBucket(bucket: TimelineBucket): List<ImmichAsset> {
            val ids = bucket.id ?: return emptyList()
            return ids.indices
                .filter { bucket.isImage?.getOrNull(it) == true && bucket.isTrashed?.getOrNull(it) != true }
                .map { i ->
                    val id = ids[i]
                    val createdAt = bucket.fileCreatedAt?.getOrNull(i)
                    val name = createdAt
                        ?.takeIf { it.length >= TIMESTAMP_TO_MINUTE }
                        ?.take(TIMESTAMP_TO_MINUTE)
                        ?.replace('T', ' ')
                        ?: id
                    val thumbhash = bucket.thumbhash?.getOrNull(i)?.ifEmpty { null }
                    ImmichAsset(id, name, parseIsoTimestamp(createdAt), thumbhash)
                }
        }

        /** Length of an ISO timestamp up to the minute: `YYYY-MM-DDTHH:MM`. */
        private const val TIMESTAMP_TO_MINUTE = 16
    }
}
