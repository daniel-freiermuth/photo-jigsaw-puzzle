package dev.daniel.jigsaw.source

import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * An HTTP GET target plus any headers (e.g. auth) needed to fetch it. [revision] identifies the
 * content (an ETag, Immich's thumbhash, …): when set, the response is cached for good, and a changed
 * picture is fetched anew because it comes with a new revision.
 */
data class HttpResource(val url: String, val headers: Map<String, String> = emptyMap(), val revision: String? = null)

/**
 * One picture available from a source. [thumbnail] is used by the gallery, [full] by the puzzle.
 * [takenAt] (epoch milliseconds; capture time where the source knows it, otherwise modification
 * time) orders the gallery newest first; null sorts last.
 */
data class RemoteImage(
    val id: String,
    val name: String,
    val thumbnail: HttpResource,
    val full: HttpResource,
    val takenAt: Long? = null,
)

/** Anything that can enumerate pictures. New backends only need to implement this. */
interface ImageSource {
    suspend fun listImages(): List<RemoteImage>
}

/** Persisted description of a source, as entered by the user. */
sealed interface SourceConfig {
    val id: String
    val label: String
    val url: String

    /** Nextcloud public share link, e.g. `https://cloud.example.com/s/AbC123`. */
    data class NextcloudShare(
        override val id: String,
        override val label: String,
        override val url: String,
        val password: String = "",
    ) : SourceConfig

    /** Immich shared link, e.g. `https://photos.example.com/share/<key>` or `/s/<slug>`. */
    data class ImmichShare(
        override val id: String,
        override val label: String,
        override val url: String,
    ) : SourceConfig

    /** A text file (one image URL per line) or JSON array of image URLs. */
    data class UrlList(
        override val id: String,
        override val label: String,
        override val url: String,
    ) : SourceConfig
}

fun SourceConfig.toImageSource(client: OkHttpClient = Http.client): ImageSource = when (this) {
    is SourceConfig.NextcloudShare -> NextcloudShareSource(url, password, client)
    is SourceConfig.ImmichShare -> ImmichShareSource(url, client)
    is SourceConfig.UrlList -> UrlListSource(url, client)
}

object Http {
    private const val CONNECT_TIMEOUT_SECONDS = 15L

    /** Generous: full-size originals from a home server over mobile data can be slow. */
    private const val READ_TIMEOUT_SECONDS = 60L

    /** Thumbnails are tens of kB and puzzle pictures a few hundred: room for thousands of each. */
    private const val DISK_CACHE_BYTES = 250L * 1024 * 1024

    /** A revisioned response never changes, so it may be reused for as long as the cache keeps it. */
    private const val PINNED_CACHE_CONTROL = "private, max-age=31536000, immutable"

    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Without [enableDiskCache] (e.g. in unit tests) nothing is cached. */
    @Volatile
    var client: OkHttpClient = base
        private set

    /** Caches responses in [dir]. Call once at app start, before any source is used. */
    fun enableDiskCache(dir: File) {
        client = withDiskCache(base, dir)
    }

    /**
     * [client] with an HTTP cache in [dir]. Responses follow the server's caching headers, except
     * those for a [HttpResource.revision], which are kept regardless.
     */
    fun withDiskCache(client: OkHttpClient, dir: File): OkHttpClient = client.newBuilder()
        .cache(Cache(dir, DISK_CACHE_BYTES))
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().tag(Revisioned::class.java) == null || !response.isSuccessful) {
                response
            } else {
                response.newBuilder()
                    .header("Cache-Control", PINNED_CACHE_CONTROL)
                    .removeHeader("Pragma")
                    .removeHeader("Expires")
                    .removeHeader("Vary")
                    .build()
            }
        }
        .build()

    fun request(resource: HttpResource): Request {
        val url = resource.url.toHttpUrl()
        return Request.Builder()
            .apply {
                if (resource.revision == null) {
                    url(url)
                } else {
                    // The cache is keyed by the full URL, but the fragment is never sent: a new revision
                    // is a new cache entry while the server sees the same request.
                    url(url.newBuilder().fragment("rev=${resource.revision}").build())
                    tag(Revisioned::class.java, Revisioned)
                }
            }
            .apply { resource.headers.forEach { (k, v) -> header(k, v) } }
            .build()
    }

    /**
     * Blocking GET returning the body bytes; throws [IOException] on non-2xx. Call from Dispatchers.IO.
     * For listings: never answered from the cache without asking the server, so new pictures show up.
     */
    fun getBytes(resource: HttpResource, client: OkHttpClient = this.client): ByteArray = execute(
        client,
        request(resource).newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build(),
        resource.url,
        ResponseBody::bytes,
    )

    /**
     * Like [getBytes], for pictures: when the server can't be reached, a cached copy is used even if
     * it is out of date, so started puzzles and seen thumbnails keep working offline.
     */
    fun getImage(resource: HttpResource, client: OkHttpClient = this.client): ByteArray {
        val request = request(resource)
        return try {
            execute(client, request, resource.url, ResponseBody::bytes)
        } catch (e: HttpStatusException) {
            throw e
        } catch (e: IOException) {
            val offline = request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()
            try {
                execute(client, offline, resource.url, ResponseBody::bytes)
            } catch (_: HttpStatusException) {
                // FORCE_CACHE answers 504 when nothing is cached: report the real network error.
                throw e
            }
        }
    }

    /** Blocking call handing the body to [read]; throws [HttpStatusException] on non-2xx, naming [url]. */
    fun <T> execute(client: OkHttpClient, request: Request, url: String, read: (ResponseBody) -> T): T =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, url)
            read(response.body)
        }

    /** Request tag: the response is pinned in the cache (see [HttpResource.revision]). */
    private object Revisioned
}

/** The server answered, but not with success. */
class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for $url")

/** The server answered successfully with something the source can't interpret. */
class UnexpectedResponseException(message: String, cause: Throwable? = null) : IOException(message, cause)
