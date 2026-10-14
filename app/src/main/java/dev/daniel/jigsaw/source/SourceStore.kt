package dev.daniel.jigsaw.source

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Persists the user's configured sources as JSON in SharedPreferences. */
class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("sources", Context.MODE_PRIVATE)

    fun load(): List<SourceConfig> = decode(prefs.getString(KEY, null) ?: "[]")

    fun save(sources: List<SourceConfig>) {
        prefs.edit { putString(KEY, encode(sources)) }
    }

    /** The `type` tag that tells the [SourceConfig] kinds apart in the stored JSON. */
    private enum class SourceType(val wire: String) {
        Nextcloud("nextcloud"),
        Immich("immich"),
        UrlList("urllist"),
        ;

        companion object {
            fun of(source: SourceConfig) = when (source) {
                is SourceConfig.NextcloudShare -> Nextcloud
                is SourceConfig.ImmichShare -> Immich
                is SourceConfig.UrlList -> UrlList
            }

            fun fromWire(wire: String) = entries.firstOrNull { it.wire == wire }
        }
    }

    companion object {
        private const val KEY = "sources"
        private const val TAG = "SourceStore"

        fun encode(sources: List<SourceConfig>): String = JSONArray().apply {
            sources.forEach { source ->
                put(
                    JSONObject().apply {
                        put("type", SourceType.of(source).wire)
                        put("id", source.id)
                        put("label", source.label)
                        put("url", source.url)
                        if (source is SourceConfig.NextcloudShare) put("password", source.password)
                    },
                )
            }
        }.toString()

        /**
         * Unknown types and unreadable entries are dropped, and unreadable data counts as no sources,
         * so a downgrade or corrupt preferences never crash the app on start.
         */
        fun decode(json: String): List<SourceConfig> {
            val array = try {
                JSONArray(json)
            } catch (e: JSONException) {
                Log.w(TAG, "Stored sources are unreadable, starting with none", e)
                return emptyList()
            }
            return (0 until array.length()).mapNotNull { index ->
                try {
                    decodeSource(array.getJSONObject(index))
                } catch (e: JSONException) {
                    Log.w(TAG, "Dropping unreadable stored source #$index", e)
                    null
                }
            }
        }

        private fun decodeSource(o: JSONObject): SourceConfig? {
            val type = SourceType.fromWire(o.optString("type")) ?: return null
            val id = o.getString("id")
            val label = o.getString("label")
            val url = o.getString("url")
            return when (type) {
                SourceType.Nextcloud -> SourceConfig.NextcloudShare(id, label, url, o.optString("password"))
                SourceType.Immich -> SourceConfig.ImmichShare(id, label, url)
                SourceType.UrlList -> SourceConfig.UrlList(id, label, url)
            }
        }
    }
}
