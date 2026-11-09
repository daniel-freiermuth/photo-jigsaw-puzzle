package dev.daniel.jigsaw.source

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dev.daniel.jigsaw.WireJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** Persists the user's configured sources as JSON in SharedPreferences. */
class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("sources", Context.MODE_PRIVATE)

    fun load(): List<SourceConfig> = decode(prefs.getString(KEY, null) ?: "[]")

    fun save(sources: List<SourceConfig>) {
        prefs.edit { putString(KEY, encode(sources)) }
    }

    companion object {
        private const val KEY = "sources"
        private const val TAG = "SourceStore"

        /** `%` and two hex digits. */
        private const val ESCAPE_LENGTH = 3

        fun encode(sources: List<SourceConfig>): String = WireJson.encodeToString(sources)

        /**
         * Unknown types and unreadable entries are dropped, and unreadable data counts as no sources,
         * so a downgrade or corrupt preferences never crash the app on start.
         */
        fun decode(json: String): List<SourceConfig> {
            val entries = try {
                WireJson.decodeFromString<List<JsonElement>>(json)
            } catch (e: SerializationException) {
                Log.w(TAG, "Stored sources are unreadable, starting with none", e)
                return emptyList()
            }
            // One entry at a time, so a single unknown `type` or broken entry costs only that source.
            return entries.mapIndexedNotNull { index, entry ->
                try {
                    WireJson.decodeFromJsonElement<SourceConfig>(entry).also(::requireBuildable)
                } catch (e: SerializationException) {
                    Log.w(TAG, "Dropping unreadable stored source #$index", e)
                    null
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Dropping stored source #$index that can't become a source", e)
                    null
                }
            }
        }

        /** Throws [IllegalArgumentException] where [toImageSource] would, without needing Android. */
        private fun requireBuildable(config: SourceConfig) {
            when (config) {
                is SourceConfig.ImmichShare -> ImmichShareSource.parseShareUrl(config.url)

                // As DocumentsContract.getTreeDocumentId requires: path segments starting `tree`, `<id>`.
                is SourceConfig.LocalFolder -> {
                    val segments = uriPathSegments(config.uri)
                    require(segments.size >= 2 && segments[0] == "tree") { "Not a folder tree URI: ${config.uri}" }
                }
            }
        }

        /**
         * What `Uri.parse(uri).pathSegments` returns, as android.net.Uri's StringUri parses it: an opaque URI has
         * none, the authority ends at `/`, `\` (WHATWG host state), `?` or `#`, the path at `?` or `#`, and segments
         * are non-empty and percent-decoded.
         */
        private fun uriPathSegments(uri: String): List<String> {
            val schemeEnd = uri.indexOf(':')
            if (schemeEnd >= 0 && uri.getOrNull(schemeEnd + 1) != '/') return emptyList()
            var pathStart = schemeEnd + 1
            if (uri.startsWith("//", pathStart)) {
                // An authority ending at `?` or `#` leaves an empty path: the search below ends it right there.
                val authorityEnd = uri.indexOfAny(charArrayOf('/', '\\', '?', '#'), pathStart + 2)
                pathStart = if (authorityEnd < 0) uri.length else authorityEnd
            }
            val pathEnd = uri.indexOfAny(charArrayOf('?', '#'), pathStart).let { if (it < 0) uri.length else it }
            return uri.substring(pathStart, pathEnd).split('/').filter { it.isNotEmpty() }.map(::percentDecode)
        }

        /** Like `Uri.decode`: only ASCII hex digits escape, and malformed escapes become U+FFFD rather than failing. */
        private fun percentDecode(segment: String): String {
            if ('%' !in segment) return segment
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < segment.length) {
                val c = segment[i]
                val escape = if (c == '%' && i + 2 < segment.length) segment.substring(i + 1, i + ESCAPE_LENGTH) else ""
                val isEscape = escape.length == 2 && escape.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                val hex = if (isEscape) escape.toInt(16) else null
                if (hex != null) {
                    bytes.write(hex)
                    i += ESCAPE_LENGTH
                } else {
                    bytes.write((if (c == '%') "\uFFFD" else c.toString()).toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
}
