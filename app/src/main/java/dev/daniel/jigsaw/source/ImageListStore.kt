package dev.daniel.jigsaw.source

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The last listing of every source, so the gallery appears at once on start and still shows a
 * source's pictures while it can't be reached. Keyed by [SourceConfig.id].
 */
class ImageListStore(private val file: File, private val ioDispatcher: CoroutineDispatcher) {

    /** Empty when nothing was saved yet or the file is unreadable. */
    suspend fun load(): Map<String, List<RemoteImage>> = withContext(ioDispatcher) {
        try {
            if (file.exists()) decode(file.readText()) else emptyMap()
        } catch (_: IOException) {
            emptyMap()
        }
    }

    /** Written to a temporary file first, so a crash never leaves a half-written list. */
    suspend fun save(listings: Map<String, List<RemoteImage>>) = withContext(ioDispatcher) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(encode(listings))
        if (!tmp.renameTo(file)) throw IOException("Could not save image list to $file")
    }

    companion object {
        private const val FORMAT_VERSION = 1

        fun encode(listings: Map<String, List<RemoteImage>>): String = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put(
                "sources",
                JSONObject().apply {
                    listings.forEach { (sourceId, images) ->
                        put(sourceId, JSONArray().apply { images.forEach { put(encodeImage(it)) } })
                    }
                },
            )
        }.toString()

        /** Anything unreadable or from another format version counts as no saved list. */
        fun decode(json: String): Map<String, List<RemoteImage>> = try {
            val root = JSONObject(json)
            if (root.optInt("version") != FORMAT_VERSION) {
                emptyMap()
            } else {
                val sources = root.getJSONObject("sources")
                sources.keys().asSequence().associateWith { id ->
                    val array = sources.getJSONArray(id)
                    (0 until array.length()).map { decodeImage(array.getJSONObject(it)) }
                }
            }
        } catch (_: JSONException) {
            emptyMap()
        }

        private fun encodeImage(image: RemoteImage) = JSONObject().apply {
            put("id", image.id)
            put("name", image.name)
            put("thumbnail", encodeResource(image.thumbnail))
            put("full", encodeResource(image.full))
            image.takenAt?.let { put("takenAt", it) }
        }

        private fun decodeImage(o: JSONObject) = RemoteImage(
            id = o.getString("id"),
            name = o.getString("name"),
            thumbnail = decodeResource(o.getJSONObject("thumbnail")),
            full = decodeResource(o.getJSONObject("full")),
            takenAt = if (o.has("takenAt")) o.getLong("takenAt") else null,
        )

        private fun encodeResource(resource: HttpResource) = JSONObject().apply {
            put("url", resource.url)
            put("headers", JSONObject(resource.headers))
            resource.revision?.let { put("revision", it) }
        }

        private fun decodeResource(o: JSONObject): HttpResource {
            val headers = o.optJSONObject("headers") ?: JSONObject()
            return HttpResource(
                url = o.getString("url"),
                headers = headers.keys().asSequence().associateWith { headers.getString(it) },
                revision = if (o.has("revision")) o.getString("revision") else null,
            )
        }
    }
}
