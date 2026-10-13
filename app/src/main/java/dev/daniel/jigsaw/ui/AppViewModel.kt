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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

sealed interface Screen {
    data object Sources : Screen
    data class Gallery(val source: SourceConfig) : Screen
    data class Puzzle(val source: SourceConfig, val image: RemoteImage) : Screen
}

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

/** A puzzle in progress. [version] bumps on every board mutation so Compose redraws. */
class PuzzleGame(val cut: CutPuzzle) {
    var version by mutableIntStateOf(0)
    var showHint by mutableStateOf(false)
    var solved by mutableStateOf(false)
    var scattered = false
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SourceStore(app)

    var sources by mutableStateOf(store.load())
        private set
    var screen by mutableStateOf<Screen>(Screen.Sources)
        private set
    var gallery by mutableStateOf<Load<List<RemoteImage>>>(Load.Loading)
        private set
    var puzzle by mutableStateOf<Load<PuzzleGame>>(Load.Loading)
        private set

    private var galleryJob: Job? = null
    private var puzzleJob: Job? = null

    private val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Lock pieces placed near their solved spot onto the board. Off by default: it makes puzzles much easier. */
    var snapToBoard by mutableStateOf(settings.getBoolean(KEY_SNAP_TO_BOARD, false))
        private set

    fun updateSnapToBoard(enabled: Boolean) {
        snapToBoard = enabled
        settings.edit().putBoolean(KEY_SNAP_TO_BOARD, enabled).apply()
    }

    /** Validates [config] (URL shape) and saves it; returns an error message on failure. */
    fun addSource(config: SourceConfig): String? {
        runCatching { config.toImageSource() }.onFailure { return it.message ?: "Invalid URL" }
        sources = sources + config
        store.save(sources)
        return null
    }

    fun removeSource(id: String) {
        sources = sources.filterNot { it.id == id }
        store.save(sources)
    }

    fun openSource(source: SourceConfig) {
        screen = Screen.Gallery(source)
        refreshGallery()
    }

    fun refreshGallery() {
        val source = (screen as? Screen.Gallery)?.source ?: return
        galleryJob?.cancel()
        gallery = Load.Loading
        galleryJob = viewModelScope.launch {
            gallery = try {
                Load.Ready(source.toImageSource().listImages())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun openImage(image: RemoteImage) {
        val source = when (val s = screen) {
            is Screen.Gallery -> s.source
            is Screen.Puzzle -> s.source
            Screen.Sources -> return
        }
        screen = Screen.Puzzle(source, image)
        puzzleJob?.cancel()
        puzzle = Load.Loading
        puzzleJob = viewModelScope.launch {
            puzzle = try {
                Load.Ready(PuzzleGame(loadPuzzle(image)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Starts a new puzzle from another random image of the current gallery. */
    fun openRandomImage() {
        val images = (gallery as? Load.Ready)?.value ?: return
        val current = (screen as? Screen.Puzzle)?.image
        val pool = images.filter { it != current }.ifEmpty { images }
        if (pool.isNotEmpty()) openImage(pool.random())
    }

    fun back() {
        when (val s = screen) {
            is Screen.Puzzle -> {
                puzzleJob?.cancel()
                puzzle = Load.Loading
                screen = Screen.Gallery(s.source)
            }
            is Screen.Gallery -> {
                galleryJob?.cancel()
                screen = Screen.Sources
            }
            Screen.Sources -> Unit
        }
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
