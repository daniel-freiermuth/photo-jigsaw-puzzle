package dev.daniel.jigsaw.source

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An HTTP GET target plus any headers (e.g. auth) needed to fetch it. */
data class HttpResource(val url: String, val headers: Map<String, String> = emptyMap())

/** One picture available from a source. [thumbnail] is used by the gallery, [full] by the puzzle. */
data class RemoteImage(
    val id: String,
    val name: String,
    val thumbnail: HttpResource,
    val full: HttpResource,
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

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun request(resource: HttpResource): Request = Request.Builder()
        .url(resource.url)
        .apply { resource.headers.forEach { (k, v) -> header(k, v) } }
        .build()

    /** Blocking GET returning the body bytes; throws [IOException] on non-2xx. Call from Dispatchers.IO. */
    fun getBytes(resource: HttpResource, client: OkHttpClient = this.client): ByteArray =
        client.newCall(request(resource)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${resource.url}")
            response.body?.bytes() ?: throw IOException("Empty body for ${resource.url}")
        }
}
