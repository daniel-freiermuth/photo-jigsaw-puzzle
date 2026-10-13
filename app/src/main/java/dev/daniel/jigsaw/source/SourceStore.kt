package dev.daniel.jigsaw.source

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists the user's configured sources as JSON in SharedPreferences. */
class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("sources", Context.MODE_PRIVATE)

    fun load(): List<SourceConfig> = decode(prefs.getString(KEY, null) ?: "[]")

    fun save(sources: List<SourceConfig>) {
        prefs.edit().putString(KEY, encode(sources)).apply()
    }

    companion object {
        private const val KEY = "sources"

        fun encode(sources: List<SourceConfig>): String = JSONArray().apply {
            sources.forEach { source ->
                put(JSONObject().apply {
                    put("id", source.id)
                    put("label", source.label)
                    put("url", source.url)
                    when (source) {
                        is SourceConfig.NextcloudShare -> {
                            put("type", "nextcloud")
                            put("password", source.password)
                        }
                        is SourceConfig.ImmichShare -> put("type", "immich")
                        is SourceConfig.UrlList -> put("type", "urllist")
                    }
                })
            }
        }.toString()

        /** Unknown types are dropped so a downgrade never crashes on newer data. */
        fun decode(json: String): List<SourceConfig> {
            val array = JSONArray(json)
            return (0 until array.length()).mapNotNull { index ->
                val o = array.getJSONObject(index)
                val id = o.getString("id")
                val label = o.getString("label")
                val url = o.getString("url")
                when (o.optString("type")) {
                    "nextcloud" -> SourceConfig.NextcloudShare(id, label, url, o.optString("password"))
                    "immich" -> SourceConfig.ImmichShare(id, label, url)
                    "urllist" -> SourceConfig.UrlList(id, label, url)
                    else -> null
                }
            }
        }
    }
}
