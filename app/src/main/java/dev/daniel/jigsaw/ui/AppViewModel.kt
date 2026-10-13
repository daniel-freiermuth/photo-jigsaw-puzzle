package dev.daniel.jigsaw.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.daniel.jigsaw.puzzle.CutPuzzle
import dev.daniel.jigsaw.puzzle.JigsawCut
import dev.daniel.jigsaw.puzzle.cutPuzzle
import dev.daniel.jigsaw.puzzle.decodeSampled
import dev.daniel.jigsaw.source.Http
import dev.daniel.jigsaw.source.RemoteImage
import dev.daniel.jigsaw.source.SourceConfig
import dev.daniel.jigsaw.source.SourceStore
import dev.daniel.jigsaw.source.toImageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

sealed interface Screen {
    data object Gallery : Screen
    data object Settings : Screen
    data class Puzzle(val image: GalleryImage) : Screen
}

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

/** An image plus the source it came from; ids are only unique within one source. */
data class GalleryImage(val source: SourceConfig, val image: RemoteImage) {
    val key get() = "${source.id}/${image.id}"
}

/** Images from every source that answered, plus a message for each source that didn't. */
data class GalleryContent(val images: List<GalleryImage>, val failures: List<String>)

/** A puzzle in progress. [version] bumps on every board mutation so Compose redraws. */
class PuzzleGame(val cut: CutPuzzle) {
    var version by mutableIntStateOf(0)
    var showHint by mutableStateOf(false)
    var solved by mutableStateOf(false)
    var scattered = false
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SourceStore(app)
    private val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sources by mutableStateOf(store.load())
        private set
    var screen by mutableStateOf<Screen>(Screen.Gallery)
        private set
    var gallery by mutableStateOf<Load<GalleryContent>>(Load.Loading)
        private set
    var puzzle by mutableStateOf<Load<PuzzleGame>>(Load.Loading)
        private set

    /** Lock pieces placed near their solved spot onto the board. Off by default: it makes puzzles much easier. */
    var snapToBoard by mutableStateOf(settings.getBoolean(KEY_SNAP_TO_BOARD, false))
        private set

    private var galleryJob: Job? = null
    private var puzzleJob: Job? = null
    private var galleryStale = false

    init {
        refreshGallery()
    }

    fun updateSnapToBoard(enabled: Boolean) {
        snapToBoard = enabled
        settings.edit().putBoolean(KEY_SNAP_TO_BOARD, enabled).apply()
    }

    /** Validates [config] (URL shape) and saves it; returns an error message on failure. */
    fun addSource(config: SourceConfig): String? {
        runCatching { config.toImageSource() }.onFailure { return it.message ?: "Invalid URL" }
        sources = sources + config
        store.save(sources)
        galleryStale = true
        return null
    }

    fun removeSource(id: String) {
        sources = sources.filterNot { it.id == id }
        store.save(sources)
        galleryStale = true
    }

    fun openSettings() {
        screen = Screen.Settings
    }

    /** Lists every source concurrently; one unreachable source doesn't hide the others' images. */
    fun refreshGallery() {
        galleryJob?.cancel()
        galleryStale = false
        val current = sources
        gallery = Load.Loading
        galleryJob = viewModelScope.launch {
            val results = current.map { source ->
                async {
                    try {
                        Result.success(source.toImageSource().listImages().map { GalleryImage(source, it) })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
            val images = results.flatMap { it.getOrDefault(emptyList()) }
            val failures = results.zip(current).mapNotNull { (result, source) ->
                result.exceptionOrNull()?.let { "${source.label}: ${it.message ?: it.javaClass.simpleName}" }
            }
            gallery = if (images.isEmpty() && failures.isNotEmpty()) {
                Load.Failed(failures.joinToString("\n"))
            } else {
                Load.Ready(GalleryContent(images, failures))
            }
        }
    }

    fun openImage(image: GalleryImage) {
        screen = Screen.Puzzle(image)
        puzzleJob?.cancel()
        puzzle = Load.Loading
        puzzleJob = viewModelScope.launch {
            puzzle = try {
                Load.Ready(PuzzleGame(loadPuzzle(image.image)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Starts a new puzzle from another random image of the gallery. */
    fun openRandomImage() {
        val images = (gallery as? Load.Ready)?.value?.images ?: return
        val current = (screen as? Screen.Puzzle)?.image
        val pool = images.filter { it != current }.ifEmpty { images }
        if (pool.isNotEmpty()) openImage(pool.random())
    }

    fun back() {
        when (screen) {
            is Screen.Puzzle -> {
                puzzleJob?.cancel()
                puzzle = Load.Loading
            }
            Screen.Settings -> if (galleryStale) refreshGallery()
            Screen.Gallery -> Unit
        }
        screen = Screen.Gallery
    }

    private suspend fun loadPuzzle(image: RemoteImage): CutPuzzle {
        val bytes = withContext(Dispatchers.IO) { Http.getBytes(image.full) }
        return withContext(Dispatchers.Default) {
            val bitmap = decodeSampled(bytes, maxSide = MAX_PUZZLE_SIDE)
            val (rows, cols) = JigsawCut.gridFor(bitmap.width, bitmap.height, TARGET_PIECES)
            cutPuzzle(bitmap, JigsawCut(rows, cols, Random.Default))
        }
    }

    private companion object {
        const val MAX_PUZZLE_SIDE = 1600
        const val TARGET_PIECES = 24
        const val KEY_SNAP_TO_BOARD = "snapToBoard"
    }
}
