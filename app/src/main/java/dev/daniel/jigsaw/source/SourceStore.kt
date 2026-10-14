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
                    WireJson.decodeFromJsonElement<SourceConfig>(entry)
                } catch (e: SerializationException) {
                    Log.w(TAG, "Dropping unreadable stored source #$index", e)
                    null
                }
            }
        }
    }
}
