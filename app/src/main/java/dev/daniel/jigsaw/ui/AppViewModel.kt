package dev.daniel.jigsaw.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.daniel.jigsaw.puzzle.CutPuzzle
import dev.daniel.jigsaw.puzzle.JigsawCut
import dev.daniel.jigsaw.puzzle.PuzzleBoard
import dev.daniel.jigsaw.puzzle.PuzzleSettings
import dev.daniel.jigsaw.puzzle.PuzzleStore
import dev.daniel.jigsaw.puzzle.SavedPuzzle
import dev.daniel.jigsaw.puzzle.cutPuzzle
import dev.daniel.jigsaw.puzzle.decodeSampled
import dev.daniel.jigsaw.source.Http
import dev.daniel.jigsaw.source.ImageListStore
import dev.daniel.jigsaw.source.RemoteImage
import dev.daniel.jigsaw.source.SourceConfig
import dev.daniel.jigsaw.source.SourceStore
import dev.daniel.jigsaw.source.toImageSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.random.Random

sealed interface Screen {
    data object Gallery : Screen
    data object Settings : Screen
    data class Puzzle(val image: GalleryImage) : Screen
}

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val reason: UiText) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

/** An image plus the source it came from; ids are only unique within one source. */
data class GalleryImage(val source: SourceConfig, val image: RemoteImage) {
    val key get() = "${source.id}/${image.id}"

    /** Seed of the puzzle a first opening cuts and scatters, so the untouched gallery tile can show it ahead. */
    val seed get() = key.hashCode().toLong()
}

/** What the puzzle screen shows for an image. */
sealed interface PuzzleContent {
    class Playing(val game: PuzzleGame) : PuzzleContent

    /** Solved earlier: just the picture. */
    class Finished(val picture: ImageBitmap) : PuzzleContent
}

/**
 * A puzzle in progress. [seed] regenerates the same cut on resume. [random] made the cut; the starting
 * scatter continues it, so a new puzzle is laid out exactly like its untouched gallery tile.
 * [version] bumps on every board mutation so Compose redraws. [scattered] is false until the pieces
 * got their starting positions.
 */
class PuzzleGame(
    val image: GalleryImage,
    val seed: Long,
    /** The piece count setting this puzzle was cut for. */
    val pieces: Int,
    val cut: CutPuzzle,
    val random: Random,
    var scattered: Boolean,
) {
    var version by mutableIntStateOf(0)
    var showHint by mutableStateOf(false)
    var solved by mutableStateOf(false)
}

class AppViewModel @JvmOverloads constructor(
    app: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Monotonic milliseconds, for deciding when the gallery is due for a refresh. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : AndroidViewModel(app) {
    private val store = SourceStore(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val puzzles = PuzzleStore(File(app.filesDir, "puzzles"))

    /** All puzzle-file access runs here, one at a time, so a load always sees the latest save. */
    private val puzzleIo = ioDispatcher.limitedParallelism(1)

    var sources by mutableStateOf(store.load())
        private set
    var screen by mutableStateOf<Screen>(Screen.Gallery)
        private set
    var puzzle by mutableStateOf<Load<PuzzleContent>>(Load.Loading)
        private set

    /** Saved puzzle state per [GalleryImage.key], for the gallery tiles; absent means never opened. */
    var tiles by mutableStateOf<Map<String, SavedPuzzle>>(emptyMap())
        private set

    var settings by mutableStateOf(
        PuzzleSettings(
            snapToBoard = prefs.getBoolean(KEY_SNAP_TO_BOARD, false),
            rotatePieces = prefs.getBoolean(KEY_ROTATE_PIECES, false),
            pieces = prefs.getInt(KEY_PIECES, JigsawCut.DEFAULT_PIECES)
                .takeIf { it in JigsawCut.PIECE_COUNTS } ?: JigsawCut.DEFAULT_PIECES,
        ),
    )
        private set

    private var puzzleJob: Job? = null

    /** The gallery's images, kept current while it is on screen. */
    val feed = GalleryFeed(
        scope = viewModelScope,
        sources = { sources },
        imageLists = ImageListStore(File(app.filesDir, "gallery/listings.json"), ioDispatcher),
        clock = clock,
        beforeShow = { content ->
            tiles = withContext(puzzleIo) {
                puzzles.deleteLegacyPreviews()
                content.images.mapNotNull { image ->
                    puzzles.load(image.source.id, image.image.id)?.let { image.key to it }
                }.toMap()
            }
        },
    )

    fun updateSettings(new: PuzzleSettings) {
        settings = new
        prefs.edit {
            putBoolean(KEY_SNAP_TO_BOARD, new.snapToBoard)
            putBoolean(KEY_ROTATE_PIECES, new.rotatePieces)
            putInt(KEY_PIECES, new.pieces)
        }
    }

    /** Validates [config] (URL shape) and saves it; false if the URL isn't a link of that kind. */
    fun addSource(config: SourceConfig): Boolean {
        try {
            config.toImageSource()
        } catch (_: IllegalArgumentException) {
            return false
        }
        sources = sources + config
        store.save(sources)
        feed.invalidate()
        return true
    }

    fun removeSource(id: String) {
        sources = sources.filterNot { it.id == id }
        store.save(sources)
        tiles = tiles.filterKeys { !it.startsWith("$id/") }
        feed.forgetSource(id)
        viewModelScope.launch(puzzleIo) { puzzles.clearSource(id) }
    }

    fun openSettings() {
        screen = Screen.Settings
    }

    /** Opens [image]'s puzzle where it was left, the finished picture if solved, or a new puzzle. */
    fun openImage(image: GalleryImage) = openImage(image, newSeed = image.seed)

    /** Like [openImage], cutting a new puzzle from [newSeed] if there is none to resume. */
    private fun openImage(image: GalleryImage, newSeed: Long) {
        saveCurrentPuzzle()
        screen = Screen.Puzzle(image)
        puzzleJob?.cancel()
        puzzle = Load.Loading
        puzzleJob = viewModelScope.launch {
            puzzle = try {
                Load.Ready(loadPuzzle(image, newSeed))
            } catch (e: IOException) {
                Load.Failed(e.toUiText())
            }
        }
    }

    /** Starts a puzzle for another random image of the gallery. */
    fun openRandomImage() {
        val images = (feed.state as? Load.Ready)?.value?.images ?: return
        val current = (screen as? Screen.Puzzle)?.image
        val pool = images.filter { it != current }.ifEmpty { images }
        if (pool.isNotEmpty()) openImage(pool.random())
    }

    /** Forgets the current image's puzzle (solved or not) and cuts a fresh one. */
    fun restartPuzzle() {
        val image = (screen as? Screen.Puzzle)?.image ?: return
        puzzle = Load.Loading // so openImage doesn't save the state being discarded
        tiles = tiles - image.key
        viewModelScope.launch(puzzleIo) { puzzles.clear(image.source.id, image.image.id) }
        openImage(image, newSeed = Random.nextLong())
    }

    /**
     * Persists the board of the puzzle on screen. Called after every move so progress survives the
     * app being killed; a solved puzzle is stored as just "solved".
     */
    fun saveCurrentPuzzle() {
        val game = ((puzzle as? Load.Ready)?.value as? PuzzleContent.Playing)?.game ?: return
        if (!game.scattered) return
        val board = game.cut.board
        val saved = if (board.isSolved) {
            SavedPuzzle.Solved
        } else {
            SavedPuzzle.InProgress(board.rows, board.cols, game.seed, board.snapshot, game.pieces)
        }
        val image = game.image
        // The gallery renders its tile from this state, so it is current even before the write lands.
        tiles = tiles + (image.key to saved)
        viewModelScope.launch(puzzleIo) {
            try {
                puzzles.save(image.source.id, image.image.id, saved)
            } catch (_: IOException) {
                // Losing one save only costs the moves since the previous one; nothing to show the user.
            }
        }
    }

    fun back() {
        when (screen) {
            is Screen.Puzzle -> {
                saveCurrentPuzzle()
                puzzleJob?.cancel()
                puzzle = Load.Loading
            }
            Screen.Settings, Screen.Gallery -> Unit
        }
        screen = Screen.Gallery
    }

    private suspend fun loadPuzzle(image: GalleryImage, newSeed: Long): PuzzleContent {
        // Read on the main thread: the settings in force when the puzzle was opened.
        val chosen = settings
        val saved = withContext(puzzleIo) { puzzles.load(image.source.id, image.image.id) }
        val bytes = withContext(ioDispatcher) { Http.getImage(image.image.full) }
        return withContext(computeDispatcher) {
            val bitmap = decodeSampled(bytes, maxSide = MAX_PUZZLE_SIDE)
            when (saved) {
                SavedPuzzle.Solved -> PuzzleContent.Finished(bitmap.asImageBitmap())
                is SavedPuzzle.InProgress -> saved.takeUnless { it.needsRecut(chosen) }
                    ?.let { resume(image, bitmap, it, chosen.rotatePieces) }
                    ?: newGame(image, bitmap, chosen, newSeed)
                null -> newGame(image, bitmap, chosen, newSeed)
            }
        }
    }

    private fun newGame(
        image: GalleryImage,
        bitmap: Bitmap,
        chosen: PuzzleSettings,
        seed: Long,
    ): PuzzleContent.Playing {
        val (rows, cols) = JigsawCut.gridFor(bitmap.width, bitmap.height, chosen.pieces)
        val random = Random(seed)
        val board = PuzzleBoard(rows, cols, cellAspect(bitmap, rows, cols), rotatable = chosen.rotatePieces)
        val cut = cutPuzzle(bitmap, JigsawCut(rows, cols, random), board)
        return PuzzleContent.Playing(PuzzleGame(image, seed, chosen.pieces, cut, random, scattered = false))
    }

    /**
     * Null if the save doesn't fit (e.g. written by an incompatible version); the caller starts afresh.
     * A puzzle with nothing on the board yet takes on the current rotation setting.
     */
    private fun resume(
        image: GalleryImage,
        bitmap: Bitmap,
        saved: SavedPuzzle.InProgress,
        rotatePieces: Boolean,
    ): PuzzleContent.Playing? = try {
        val aspect = cellAspect(bitmap, saved.rows, saved.cols)
        val current = saved.withRotation(rotatePieces, aspect)
        val board = PuzzleBoard(current.rows, current.cols, current.board, aspect)
        val random = Random(current.seed)
        val cut = cutPuzzle(bitmap, JigsawCut(current.rows, current.cols, random), board)
        PuzzleContent.Playing(PuzzleGame(image, current.seed, current.pieces, cut, random, scattered = true))
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun cellAspect(bitmap: Bitmap, rows: Int, cols: Int) =
        (bitmap.width.toFloat() / cols) / (bitmap.height.toFloat() / rows)

    private companion object {
        const val MAX_PUZZLE_SIDE = 1600
        const val KEY_SNAP_TO_BOARD = "snapToBoard"
        const val KEY_ROTATE_PIECES = "rotatePieces"
        const val KEY_PIECES = "pieces"
    }
}
