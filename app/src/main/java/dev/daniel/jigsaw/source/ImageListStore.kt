package dev.daniel.jigsaw.source

import android.util.Log
import dev.daniel.jigsaw.WireJson
import dev.daniel.jigsaw.writeTextAtomically
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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
        } catch (e: IOException) {
            Log.w(TAG, "Could not read saved image lists from $file, starting with none", e)
            emptyMap()
        }
    }

    /** Written atomically, so a crash never leaves a half-written list. */
    suspend fun save(listings: Map<String, List<RemoteImage>>) = withContext(ioDispatcher) {
        file.writeTextAtomically(encode(listings))
    }

    companion object {
        /** 2: pictures are tagged `http` or `document` resources. */
        private const val FORMAT_VERSION = 2
        private const val TAG = "ImageListStore"

        fun encode(listings: Map<String, List<RemoteImage>>): String =
            WireJson.encodeToString(ListingsFile(FORMAT_VERSION, listings))

        /** Anything unreadable or from another format version counts as no saved list. */
        fun decode(json: String): Map<String, List<RemoteImage>> = try {
            val saved = WireJson.decodeFromString<ListingsFile>(json)
            if (saved.version == FORMAT_VERSION) saved.sources else emptyMap()
        } catch (e: SerializationException) {
            Log.w(TAG, "Saved image lists are unreadable, starting with none", e)
            emptyMap()
        }
    }

    /** The file format: listings keyed by [SourceConfig.id], each image as a [RemoteImage]. */
    @Serializable
    private class ListingsFile(val version: Int, val sources: Map<String, List<RemoteImage>>)
}
