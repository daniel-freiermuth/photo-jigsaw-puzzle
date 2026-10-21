package dev.daniel.jigsaw.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.daniel.jigsaw.puzzle.BitmapPicture
import dev.daniel.jigsaw.puzzle.CutPuzzle
import dev.daniel.jigsaw.puzzle.Grid
import dev.daniel.jigsaw.puzzle.JigsawCut
import dev.daniel.jigsaw.puzzle.PieceCount
import dev.daniel.jigsaw.puzzle.PuzzleKey
import dev.daniel.jigsaw.puzzle.PuzzlePicture
import dev.daniel.jigsaw.puzzle.PuzzleSettings
import dev.daniel.jigsaw.puzzle.PuzzleStore
import dev.daniel.jigsaw.puzzle.SavedPuzzle
import dev.daniel.jigsaw.puzzle.cutNew
import dev.daniel.jigsaw.puzzle.cutSaved
import dev.daniel.jigsaw.puzzle.decodeSampled
import dev.daniel.jigsaw.source.ImageListStore
import dev.daniel.jigsaw.source.ImageResource
import dev.daniel.jigsaw.source.RemoteImage
import dev.daniel.jigsaw.source.SourceConfig
import dev.daniel.jigsaw.source.SourceStore
import dev.daniel.jigsaw.source.read
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
    /** Plain-string id, for list item keys (which must be Bundle-saveable) and [seed]. */
    val key get() = "${source.id}/${image.id}"

    val puzzleKey get() = PuzzleKey(sourceId = source.id, imageId = image.id)

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
    val pieces: PieceCount,
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
    clock: () -> Long = SystemClock::elapsedRealtime,
    /** Reads a puzzle's picture or thumbnail; blocking, run on [ioDispatcher]. */
    private val fetchImage: (ImageResource) -> ByteArray = { it.read(app.contentResolver) },
    /** Decodes downloaded bytes to at most the given longest side; CPU-heavy, run on [computeDispatcher]. */
    private val decodePicture: (ByteArray, Int) -> PuzzlePicture = { bytes, maxSide ->
        BitmapPicture(decodeSampled(bytes, maxSide))
    },
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

    /** Saved puzzle state per [GalleryImage.puzzleKey], for the gallery tiles; absent means never opened. */
    var tiles by mutableStateOf<Map<PuzzleKey, SavedPuzzle>>(emptyMap())
        private set

    var settings by mutableStateOf(
        PuzzleSettings(
            snapToBoard = prefs.getBoolean(KEY_SNAP_TO_BOARD, false),
            rotatePieces = prefs.getBoolean(KEY_ROTATE_PIECES, false),
            pieces = PieceCount.fromTarget(prefs.getInt(KEY_PIECES, PieceCount.DEFAULT.target)),
        ),
    )
        private set

    private var puzzleJob: Job? = null

    /** Counts [saveCurrentPuzzle] calls, so a failed save can tell whether a later one superseded it. */
    private var saves = 0

    /** The gallery's images, kept current while it is on screen. */
    val feed = GalleryFeed(
        scope = viewModelScope,
        sources = { sources },
        imageLists = ImageListStore(File(app.filesDir, "gallery/listings.json"), ioDispatcher),
        clock = clock,
        list = { it.toImageSource(app.contentResolver).listImages() },
        beforeShow = { content ->
            tiles = withContext(puzzleIo) {
                puzzles.deleteLegacyPreviews()
                content.images.mapNotNull { image ->
                    puzzles.load(image.puzzleKey)?.let { image.puzzleKey to it }
                }.toMap()
            }
        },
    )

    init {
        // Sources dropped on load (types an update removed) never pass through removeSource. Queued first
        // on puzzleIo, so it runs before any save of a source added from here on.
        val kept = sources.map { it.id }
        viewModelScope.launch(puzzleIo) { puzzles.keepOnlySources(kept) }
    }

    fun updateSettings(new: PuzzleSettings) {
        settings = new
        prefs.edit {
            putBoolean(KEY_SNAP_TO_BOARD, new.snapToBoard)
            putBoolean(KEY_ROTATE_PIECES, new.rotatePieces)
            putInt(KEY_PIECES, new.pieces.target)
        }
    }

    /**
     * Validates [config] (link shape or folder) and saves it; false if it isn't a source of that kind.
     * A folder's read access is kept across restarts until the source is removed.
     */
    fun addSource(config: SourceConfig): Boolean {
        val resolver = getApplication<Application>().contentResolver
        val accepted = try {
            config.toImageSource(resolver)
            if (config is SourceConfig.LocalFolder) {
                resolver.takePersistableUriPermission(config.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            true
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (accepted) {
            sources = sources + config
            store.save(sources)
            feed.invalidate()
        }
        return accepted
    }

    fun removeSource(id: String) {
        val removed = sources.find { it.id == id } ?: return
        sources = sources - removed
        store.save(sources)
        val folder = (removed as? SourceConfig.LocalFolder)?.uri
        if (folder != null && sources.none { it is SourceConfig.LocalFolder && it.uri == folder }) releaseFolder(folder)
        tiles = tiles.filterKeys { it.sourceId != id }
        feed.forgetSource(id)
        viewModelScope.launch(puzzleIo) { puzzles.clearSource(id) }
    }

    private fun releaseFolder(uri: String) {
        try {
            getApplication<Application>().contentResolver
                .releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Already gone (e.g. revoked in the system settings): nothing left to release.
            Log.w(TAG, "Folder access was already released", e)
        }
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
        tiles = tiles - image.puzzleKey
        viewModelScope.launch(puzzleIo) { puzzles.clear(image.puzzleKey) }
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
            SavedPuzzle.InProgress(board.grid, game.seed, board.snapshot, game.pieces)
        }
        val image = game.image
        val key = image.puzzleKey
        // The gallery renders its tile from this state, so it is current even before the write lands.
        tiles = tiles + (key to saved)
        val save = ++saves
        viewModelScope.launch {
            val persisted = withContext(puzzleIo) {
                try {
                    puzzles.save(key, saved)
                    saved
                } catch (e: IOException) {
                    // Losing one save only costs the moves since the previous one; nothing to show the user.
                    Log.w(TAG, "Could not save puzzle ${image.key}", e)
                    puzzles.load(key)
                }
            }
            // Put the tile back to what is on disk, unless a later save, restart or reload replaced it.
            if (persisted !== saved && save == saves && tiles[key] === saved) {
                tiles = if (persisted == null) tiles - key else tiles + (key to persisted)
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
        val saved = withContext(puzzleIo) { puzzles.load(image.puzzleKey) }
        val bytes = withContext(ioDispatcher) { fetchImage(image.image.full) }
        return withContext(computeDispatcher) {
            val picture = decodePicture(bytes, MAX_PUZZLE_SIDE)
            when (saved) {
                SavedPuzzle.Solved -> PuzzleContent.Finished(picture.asImageBitmap())

                is SavedPuzzle.InProgress -> saved.takeUnless { it.needsRecut(chosen) }
                    ?.let { resume(image, picture, it, chosen.rotatePieces) }
                    ?: newGame(image, bytes, picture, chosen, newSeed)

                null -> newGame(image, bytes, picture, chosen, newSeed)
            }
        }
    }

    private suspend fun newGame(
        image: GalleryImage,
        bytes: ByteArray,
        picture: PuzzlePicture,
        chosen: PuzzleSettings,
        seed: Long,
    ): PuzzleContent.Playing {
        val (cut, random) = picture.cutNew(chosen, seed, tileGrid(image, bytes, picture, chosen.pieces))
        return PuzzleContent.Playing(PuzzleGame(image, seed, chosen.pieces, cut, random, scattered = false))
    }

    /**
     * The grid [image]'s untouched gallery tile shows, which a new puzzle is cut into so it matches the tile.
     * The tile picks it from the thumbnail: a separately rounded rendition whose aspect can differ from
     * [picture]'s ([bytes] decoded) just enough to land [JigsawCut.gridFor] on another grid. [picture]
     * decides when the thumbnail can't be downloaded or decoded, so the puzzle still opens.
     */
    private suspend fun tileGrid(
        image: GalleryImage,
        bytes: ByteArray,
        picture: PuzzlePicture,
        pieces: PieceCount,
    ): Grid {
        val resource = image.image.thumbnail
        val thumbnail = try {
            val thumbnailBytes = if (resource == image.image.full) {
                bytes
            } else {
                withContext(ioDispatcher) { fetchImage(resource) }
            }
            decodePicture(thumbnailBytes, MAX_SIDE)
        } catch (_: IOException) {
            picture
        }
        return JigsawCut.gridFor(thumbnail.width, thumbnail.height, pieces)
    }

    /** A puzzle with nothing on the board yet takes on the current rotation setting. */
    private fun resume(
        image: GalleryImage,
        picture: PuzzlePicture,
        saved: SavedPuzzle.InProgress,
        rotatePieces: Boolean,
    ): PuzzleContent.Playing {
        val (cut, random) = picture.cutSaved(saved, rotatePieces)
        return PuzzleContent.Playing(PuzzleGame(image, saved.seed, saved.pieces, cut, random, scattered = true))
    }

    private companion object {
        const val TAG = "AppViewModel"
        const val MAX_PUZZLE_SIDE = 1600
        const val KEY_SNAP_TO_BOARD = "snapToBoard"
        const val KEY_ROTATE_PIECES = "rotatePieces"
        const val KEY_PIECES = "pieces"
    }
}
