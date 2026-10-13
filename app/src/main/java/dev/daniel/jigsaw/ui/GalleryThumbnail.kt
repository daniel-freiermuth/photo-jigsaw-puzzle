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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException

/** In-memory thumbnail cache with bounded loading concurrency. */
private class ThumbnailLoader(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / HEAP_FRACTION).toInt(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * BYTES_PER_PIXEL
    }
    private val loads = Semaphore(MAX_PARALLEL_LOADS)

    fun cached(key: String): ImageBitmap? = cache.get(key)

    /** Decodes the bytes from [read] (blocking I/O, run on the I/O dispatcher) and caches them under [key]. */
    suspend fun load(key: String, read: () -> ByteArray): ImageBitmap = cached(key) ?: loads.withPermit {
        withContext(ioDispatcher) {
            decodeSampled(read(), maxSide = MAX_SIDE).asImageBitmap()
        }.also { cache.put(key, it) }
    }

    private companion object {
        /** Use up to 1/8 of the heap for thumbnails. */
        const val HEAP_FRACTION = 8
        const val BYTES_PER_PIXEL = 4
        const val MAX_PARALLEL_LOADS = 6
        const val MAX_SIDE = 400
    }
}

private val thumbnails = ThumbnailLoader()

private sealed interface ThumbState {
    data object Loading : ThumbState
    data object Failed : ThumbState
    data class Ready(val image: ImageBitmap) : ThumbState
}

/**
 * Gallery tile: the puzzle as currently laid out if it is in progress ([preview]), otherwise the
 * picture itself (not started yet, or solved).
 */
@Composable
fun GalleryThumbnail(item: GalleryImage, preview: PuzzlePreview?, modifier: Modifier = Modifier) {
    if (preview != null) {
        // The preview shows the board plus loose pieces; fit it whole rather than cropping.
        LoadedImage(
            key = "preview:${preview.file.path}@${preview.version}",
            read = { preview.file.readBytes() },
            contentDescription = item.image.name,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        val resource = item.image.thumbnail
        LoadedImage(
            key = resource.url,
            read = { Http.getBytes(resource) },
            contentDescription = item.image.name,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

@Composable
private fun LoadedImage(
    key: String,
    read: () -> ByteArray,
    contentDescription: String,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    val state by produceState<ThumbState>(
        initialValue = thumbnails.cached(key)?.let { ThumbState.Ready(it) } ?: ThumbState.Loading,
        key1 = key,
    ) {
        if (value is ThumbState.Ready) return@produceState
        value = try {
            ThumbState.Ready(thumbnails.load(key, read))
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
                contentScale = contentScale,
            )
        }
    }
}
