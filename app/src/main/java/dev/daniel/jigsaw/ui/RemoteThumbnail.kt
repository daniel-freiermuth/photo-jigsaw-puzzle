package dev.daniel.jigsaw.ui

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.daniel.jigsaw.puzzle.decodeSampled
import dev.daniel.jigsaw.source.Http
import dev.daniel.jigsaw.source.HttpResource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException

/** In-memory thumbnail cache with bounded download concurrency. */
private class ThumbnailLoader(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / HEAP_FRACTION).toInt(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * BYTES_PER_PIXEL
    }
    private val downloads = Semaphore(MAX_PARALLEL_DOWNLOADS)

    fun cached(resource: HttpResource): ImageBitmap? = cache.get(resource.url)

    suspend fun load(resource: HttpResource): ImageBitmap = cached(resource) ?: downloads.withPermit {
        withContext(ioDispatcher) {
            decodeSampled(Http.getBytes(resource), maxSide = MAX_SIDE).asImageBitmap()
        }.also { cache.put(resource.url, it) }
    }

    private companion object {
        /** Use up to 1/8 of the heap for thumbnails. */
        const val HEAP_FRACTION = 8
        const val BYTES_PER_PIXEL = 4
        const val MAX_PARALLEL_DOWNLOADS = 6
        const val MAX_SIDE = 400
    }
}

private val thumbnails = ThumbnailLoader()

private sealed interface ThumbState {
    data object Loading : ThumbState
    data object Failed : ThumbState
    data class Ready(val image: ImageBitmap) : ThumbState
}

@Composable
fun RemoteThumbnail(resource: HttpResource, contentDescription: String, modifier: Modifier = Modifier) {
    val state by produceState<ThumbState>(
        initialValue = thumbnails.cached(resource)?.let { ThumbState.Ready(it) } ?: ThumbState.Loading,
        key1 = resource,
    ) {
        if (value is ThumbState.Ready) return@produceState
        value = try {
            ThumbState.Ready(thumbnails.load(resource))
        } catch (_: IOException) {
            ThumbState.Failed
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when (val s = state) {
            ThumbState.Failed -> Text("⚠", style = MaterialTheme.typography.titleLarge)
            ThumbState.Loading -> Unit
            is ThumbState.Ready -> Image(
                s.image,
                contentDescription,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
