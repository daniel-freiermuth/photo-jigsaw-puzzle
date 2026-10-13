package dev.daniel.jigsaw.ui

import android.graphics.Bitmap
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
import dev.daniel.jigsaw.puzzle.JigsawCut
import dev.daniel.jigsaw.puzzle.decodeSampled
import dev.daniel.jigsaw.puzzle.renderUntouchedPile
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

    /** Runs [produce] (blocking: I/O and decoding) on the I/O dispatcher and caches the result under [key]. */
    suspend fun load(key: String, produce: () -> Bitmap): ImageBitmap = cached(key) ?: loads.withPermit {
        withContext(ioDispatcher) { produce().asImageBitmap() }.also { cache.put(key, it) }
    }

    private companion object {
        /** Use up to 1/8 of the heap for thumbnails. */
        const val HEAP_FRACTION = 8
        const val BYTES_PER_PIXEL = 4
        const val MAX_PARALLEL_LOADS = 6
    }
}

private val thumbnails = ThumbnailLoader()

/** Thumbnails and puzzle previews are rendered at this size (longer side, pixels). */
private const val MAX_SIDE = 400

private sealed interface ThumbState {
    data object Loading : ThumbState
    data object Failed : ThumbState
    data class Ready(val image: ImageBitmap) : ThumbState
}

/**
 * Gallery tile, by the state of the image's puzzle: never opened → a pile of pieces cut from the
 * thumbnail; in progress → its saved [PuzzleTile.InProgress] snapshot; solved → the picture.
 */
@Composable
fun GalleryThumbnail(item: GalleryImage, tile: PuzzleTile?, modifier: Modifier = Modifier) {
    val resource = item.image.thumbnail
    when (tile) {
        is PuzzleTile.InProgress -> LoadedImage(
            key = "preview:${tile.preview.file.path}@${tile.preview.version}",
            produce = { decodeSampled(tile.preview.file.readBytes(), MAX_SIDE) },
            contentDescription = item.image.name,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
        PuzzleTile.Solved -> LoadedImage(
            key = resource.url,
            produce = { decodeSampled(Http.getBytes(resource), MAX_SIDE) },
            contentDescription = item.image.name,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        null -> LoadedImage(
            key = "pile:${resource.url}",
            produce = {
                val picture = decodeSampled(Http.getBytes(resource), MAX_SIDE)
                // Seeded by the image so the heap looks the same every time the tile is drawn.
                renderUntouchedPile(picture, JigsawCut.DEFAULT_PIECES, item.key.hashCode().toLong(), MAX_SIDE)
            },
            contentDescription = item.image.name,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}

@Composable
private fun LoadedImage(
    key: String,
    produce: () -> Bitmap,
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
            ThumbState.Ready(thumbnails.load(key, produce))
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
