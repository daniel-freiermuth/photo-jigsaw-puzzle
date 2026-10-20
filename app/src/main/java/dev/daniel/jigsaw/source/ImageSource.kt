package dev.daniel.jigsaw.source

import android.content.ContentResolver
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Where a picture's bytes come from. [revision] identifies the content (Immich's thumbhash, a file's
 * modification time and size, …): a changed picture comes with a new revision. Serializable for
 * [ImageListStore]; the `type` tag comes from the [SerialName]s.
 */
@Serializable
sealed interface ImageResource {
    /** The picture's address (URL or content URI); together with [revision] it names one version of it. */
    val location: String
    val revision: String?
}

/**
 * An HTTP GET target plus any headers needed to fetch it. With a [revision], the response is cached
 * for good, and a changed picture is fetched anew because it comes with a new revision.
 */
@Serializable
@SerialName("http")
data class HttpResource(
    val url: String,
    @EncodeDefault val headers: Map<String, String> = emptyMap(),
    override val revision: String? = null,
) : ImageResource {
    override val location get() = url
}

/** A document on the device, by its `content://` URI (see [LocalFolderSource]). */
@Serializable
@SerialName("document")
data class DocumentResource(val uri: String, override val revision: String? = null) : ImageResource {
    override val location get() = uri
}

/** The picture's bytes. Blocking: call from Dispatchers.IO. */
fun ImageResource.read(resolver: ContentResolver): ByteArray = when (this) {
    is HttpResource -> Http.getImage(this)
    is DocumentResource -> readDocument(resolver, uri)
}

/**
 * One picture available from a source. [thumbnail] is used by the gallery, [full] by the puzzle.
 * [takenAt] (epoch milliseconds; capture time where the source knows it, otherwise modification
 * time) orders the gallery newest first; null sorts last. Serializable for [ImageListStore].
 */
@Serializable
data class RemoteImage(
    val id: String,
    val name: String,
    val thumbnail: ImageResource,
    val full: ImageResource,
    val takenAt: Long? = null,
)

/** Anything that can enumerate pictures. New backends only need to implement this. */
interface ImageSource {
    suspend fun listImages(): List<RemoteImage>
}

/**
 * Persisted description of a source, as entered by the user. This is also the stored format (see
 * [SourceStore]): the `type` tag comes from the [SerialName]s and the fields from the property names.
 */
@Serializable
sealed interface SourceConfig {
    val id: String
    val label: String

    /** Immich shared link, e.g. `https://photos.example.com/share/<key>` or `/s/<slug>`. */
    @Serializable
    @SerialName("immich")
    data class ImmichShare(
        override val id: String,
        override val label: String,
        val url: String,
    ) : SourceConfig

    /** A folder on the device, as the `content://` tree URI the system folder picker returned. */
    @Serializable
    @SerialName("folder")
    data class LocalFolder(
        override val id: String,
        override val label: String,
        val uri: String,
    ) : SourceConfig
}

/** Throws [IllegalArgumentException] if the config's link or folder isn't one of its kind. */
fun SourceConfig.toImageSource(resolver: ContentResolver, client: OkHttpClient = Http.client): ImageSource =
    when (this) {
        is SourceConfig.ImmichShare -> ImmichShareSource(url, client)
        is SourceConfig.LocalFolder -> LocalFolderSource(ResolverDocumentTree(resolver, uri))
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

    /** [getBytes] decoded as UTF-8, for JSON and text listings. */
    fun getText(resource: HttpResource, client: OkHttpClient = this.client): String =
        String(getBytes(resource, client), Charsets.UTF_8)

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

/**
 * This URL's scheme/host/port with the path replaced by the non-empty [segments] plus a trailing
 * slash (so relative path appends work uniformly); query and fragment are dropped.
 */
internal fun HttpUrl.baseWithPath(segments: List<String>): HttpUrl = newBuilder()
    .query(null)
    .fragment(null)
    .encodedPath("/")
    .apply { segments.filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }
    .addPathSegment("")
    .build()

/** The server answered, but not with success. */
class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for $url")

/** The server answered successfully with something the source can't interpret. */
class UnexpectedResponseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The folder has more subfolders than a source walks; listing only some of them would hide pictures without a word. */
class TooManyFoldersException(val limit: Int) : IOException("The folder has more than $limit subfolders")
