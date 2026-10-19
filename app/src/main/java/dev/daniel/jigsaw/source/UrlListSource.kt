package dev.daniel.jigsaw.source

import dev.daniel.jigsaw.WireJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Generic fallback: a manifest file hosted anywhere (static site, S3 bucket, gist…).
 * Either a JSON array of URL strings, or plain text with one URL per line (`#` comments allowed).
 * Relative URLs resolve against the manifest location.
 */
class UrlListSource(
    manifestUrl: String,
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageSource {

    private val manifestUrl = manifestUrl.trim().toHttpUrl().toString()

    override suspend fun listImages(): List<RemoteImage> = withContext(ioDispatcher) {
        val body = Http.getText(HttpResource(manifestUrl), client)
        val urls = try {
            parseManifest(manifestUrl, body)
        } catch (e: SerializationException) {
            throw UnexpectedResponseException("Malformed URL list at $manifestUrl", e)
        }
        urls.map { url ->
            RemoteImage(
                id = url,
                name = url.toHttpUrl().pathSegments.lastOrNull { it.isNotEmpty() } ?: url,
                thumbnail = HttpResource(url),
                full = HttpResource(url),
            )
        }
    }

    companion object {
        fun parseManifest(manifestUrl: String, body: String): List<String> {
            // Editors saving "UTF-8 with BOM" prepend U+FEFF, which trim() keeps.
            val trimmed = body.removePrefix("\uFEFF").trim()
            val entries = if (trimmed.startsWith("[")) {
                WireJson.decodeFromString<List<String>>(trimmed)
            } else {
                trimmed.lineSequence().toList()
            }
            val base = manifestUrl.trim().toHttpUrl()
            return entries
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { base.resolve(it)?.toString() }
                .distinct()
        }
    }
}
