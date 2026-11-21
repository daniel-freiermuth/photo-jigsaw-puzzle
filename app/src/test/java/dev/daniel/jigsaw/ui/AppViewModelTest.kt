package dev.daniel.jigsaw.ui

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import dev.daniel.jigsaw.AppConfig
import dev.daniel.jigsaw.AppConfigSerializer
import dev.daniel.jigsaw.appConfigMigrations
import dev.daniel.jigsaw.puzzle.Bounds
import dev.daniel.jigsaw.puzzle.CutPuzzle
import dev.daniel.jigsaw.puzzle.JigsawCut
import dev.daniel.jigsaw.puzzle.PieceCount
import dev.daniel.jigsaw.puzzle.PuzzleBoard
import dev.daniel.jigsaw.puzzle.PuzzleKey
import dev.daniel.jigsaw.puzzle.PuzzlePicture
import dev.daniel.jigsaw.puzzle.PuzzleSettings
import dev.daniel.jigsaw.puzzle.PuzzleStore
import dev.daniel.jigsaw.puzzle.SavedPuzzle
import dev.daniel.jigsaw.source.HttpResource
import dev.daniel.jigsaw.source.ImageListStore
import dev.daniel.jigsaw.source.ImageResource
import dev.daniel.jigsaw.source.ImageSource
import dev.daniel.jigsaw.source.RemoteImage
import dev.daniel.jigsaw.source.SourceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    @TempDir
    lateinit var tmp: File

    private val source = SourceConfig.ImmichShare("src", "Pictures", "https://p/share/k")
    private val image = GalleryImage(
        source,
        RemoteImage("a.jpg", "a", HttpResource("https://p/thumb/a.jpg"), HttpResource("https://p/a.jpg")),
    )

    /** Reads a picture, as its location; a test makes it throw to take the network away. */
    private var fetch: (ImageResource) -> ByteArray = { it.location.encodeToByteArray() }

    /** Pixel size each download decodes to, by location; anything else decodes to 1200 × 800. */
    private val sizes = mutableMapOf<String, Pair<Int, Int>>()

    /** Checks a source being added, as toImageSource does; a test makes it throw to reject the config. */
    private var openSource: (SourceConfig) -> ImageSource = { NoImages }

    /** Folder URIs whose read access the view model kept; a test makes it throw to refuse the grant. */
    private val keptFolders = mutableListOf<String>()
    private var keepFolderAccess: (String) -> Unit = { keptFolders += it }

    private val folder = SourceConfig.LocalFolder("dir", "Camera", "content://docs/tree/primary%3ADCIM")

    /** The view model's puzzle files, read directly. */
    private val saved get() = PuzzleStore(File(tmp, "puzzles")).load(image.puzzleKey)

    @BeforeEach
    fun mainLikeProduction() {
        // viewModelScope runs on Dispatchers.Main.immediate: launched code runs at once, up to its
        // first switch to another dispatcher.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /**
     * Runs the config stores. Not the test's backgroundScope: advanceUntilIdle doesn't wait for background
     * work, so the sources and settings would never be read.
     */
    private val stores = Job()

    @AfterEach
    fun resetMain() {
        stores.cancel()
        Dispatchers.resetMain()
    }

    /**
     * I/O and decoding queue on one test dispatcher and run when the test advances. The sources and settings
     * are read before this returns. [storeScope] runs the config store; cancel it to restart the app.
     * [wrapStore] lets a test stand between the view model and the store.
     */
    private fun TestScope.viewModel(
        app: TestApp = TestApp(tmp),
        storeScope: CoroutineScope = CoroutineScope(stores),
        wrapStore: (DataStore<AppConfig>) -> DataStore<AppConfig> = { it },
    ): AppViewModel {
        val background = StandardTestDispatcher(testScheduler)
        val configStore = DataStoreFactory.create(
            serializer = AppConfigSerializer,
            migrations = appConfigMigrations(app),
            scope = storeScope + background,
        ) { File(app.filesDir, "datastore/config.json") }
        return AppViewModel(
            app,
            ioDispatcher = background,
            computeDispatcher = background,
            clock = { testScheduler.currentTime },
            fetchImage = { fetch(it) },
            decodePicture = { bytes, _ -> decode(bytes) },
            configStore = wrapStore(configStore),
            openSource = { openSource(it) },
            keepFolderAccess = { keepFolderAccess(it) },
        ).also {
            advanceUntilIdle()
            assertTrue(it.loaded)
        }
    }

    private fun decode(bytes: ByteArray) = sizes[bytes.decodeToString()]?.let { (w, h) -> FakePicture(w, h) }
        ?: FakePicture()

    private val AppViewModel.content get() = (puzzle as Load.Ready).value

    private val AppViewModel.game get() = (content as PuzzleContent.Playing).game

    /** What the puzzle screen does on first layout: every piece goes to a tray beside the board. */
    private fun PuzzleGame.scatter() {
        val board = cut.board
        board.scatter(Bounds(board.cols + 1f, 0f, board.cols + 4f, board.rows.toFloat()), random)
        scattered = true
    }

    /** Drags piece 0 onto its solved spot, which starts the puzzle. */
    private fun PuzzleGame.placeFirstPiece() {
        val board = cut.board
        board.moveGroup(0, -board.x(0), -board.y(0))
        board.drop(0, snapToBoard = false)
    }

    /** Drags every piece onto its solved spot, joining them all into one group. */
    private fun PuzzleGame.solve() {
        val board = cut.board
        for (i in 0 until board.size) {
            board.moveGroup(i, (i % board.cols) - board.x(i), (i / board.cols) - board.y(i))
            board.drop(i, snapToBoard = false)
        }
    }

    /**
     * Makes every later write of [image]'s save fail, once one has landed: a directory sits where the
     * store writes its temp file. Delete the returned directory to let writes through again.
     */
    private fun blockSaves(): File {
        val file = File(tmp, "puzzles").walk().single { it.isFile && it.name.endsWith(".json") }
        return File(file.path + ".tmp").apply { check(mkdir()) }
    }

    @Test
    fun `start over cuts a fresh puzzle instead of reopening the discarded board`() = runTest {
        val vm = viewModel()
        vm.openImage(image)
        advanceUntilIdle()
        val old = vm.game
        old.scatter()
        old.placeFirstPiece()
        vm.saveCurrentPuzzle()
        advanceUntilIdle()
        assertInstanceOf(SavedPuzzle.InProgress::class.java, saved)

        vm.restartPuzzle()
        advanceUntilIdle()

        val fresh = vm.game
        assertFalse(fresh.scattered)
        assertNotEquals(old.seed, fresh.seed)
        assertNull(saved)
        assertNull(vm.tiles[image.puzzleKey])
    }

    @Test
    fun `a solved board is saved as just solved and reopens as the finished picture`() = runTest {
        val vm = viewModel()
        vm.openImage(image)
        advanceUntilIdle()
        vm.game.scatter()
        vm.game.solve()
        assertTrue(vm.game.cut.board.isSolved)

        vm.saveCurrentPuzzle()
        advanceUntilIdle()
        assertEquals(SavedPuzzle.Solved, vm.tiles[image.puzzleKey])
        assertEquals(SavedPuzzle.Solved, saved)

        vm.back()
        vm.openImage(image)
        advanceUntilIdle()
        assertInstanceOf(PuzzleContent.Finished::class.java, vm.content)
    }

    @Test
    fun `play again on a solved picture forgets it and opens a new game`() = runTest {
        val vm = viewModel()
        vm.openImage(image)
        advanceUntilIdle()
        vm.game.scatter()
        vm.game.solve()
        vm.back()
        vm.openImage(image)
        advanceUntilIdle()
        assertInstanceOf(PuzzleContent.Finished::class.java, vm.content)

        vm.restartPuzzle()
        advanceUntilIdle()

        assertFalse(vm.game.scattered)
        assertNull(vm.tiles[image.puzzleKey])
        assertNull(saved)
    }

    @Test
    fun `a puzzle not scattered yet is never saved`() = runTest {
        val vm = viewModel()
        vm.openImage(image)
        advanceUntilIdle()

        // Its pieces still sit on their solved cells: saved, it would reload as started and nearly done.
        vm.saveCurrentPuzzle()
        vm.back()
        advanceUntilIdle()

        assertNull(saved)
        assertEquals(emptyMap<PuzzleKey, SavedPuzzle>(), vm.tiles)
    }

    @Test
    fun `an unfinished board is saved and tiled with its seed, piece count and layout`() = runTest {
        val vm = viewModel()
        vm.updateSettings { it.copy(pieces = PieceCount.P48) }
        vm.openImage(image)
        advanceUntilIdle()
        val game = vm.game
        game.scatter()
        game.placeFirstPiece()

        vm.saveCurrentPuzzle()
        advanceUntilIdle()

        val board = game.cut.board
        val expected = SavedPuzzle.InProgress(
            board.grid,
            image.seed,
            board.snapshot,
            pieces = PieceCount.P48,
        )
        assertEquals(image.seed, game.seed)
        assertEquals(expected, vm.tiles[image.puzzleKey])
        assertEquals(expected, saved)
    }

    @Test
    fun `going back saves the board and leaves no puzzle loaded`() = runTest {
        val vm = viewModel()
        vm.openImage(image)
        advanceUntilIdle()
        val game = vm.game
        game.scatter()
        game.placeFirstPiece()

        vm.back()
        advanceUntilIdle()

        assertEquals(Screen.Gallery, vm.screen)
        assertEquals(Load.Loading, vm.puzzle)
        assertEquals(game.cut.board.snapshot, (saved as SavedPuzzle.InProgress).board)
    }

    @Test
    fun `going back while the puzzle loads cancels the load`() = runTest {
        val vm = viewModel()
        vm.openImage(image)

        vm.back()
        advanceUntilIdle()

        assertEquals(Screen.Gallery, vm.screen)
        assertEquals(Load.Loading, vm.puzzle)
    }

    @Test
    fun `reopening a started puzzle resumes its board even after the piece count changed`() = runTest {
        val vm = viewModel()
        vm.openImage(image)
        advanceUntilIdle()
        val old = vm.game
        old.scatter()
        old.placeFirstPiece()
        vm.back()
        vm.updateSettings { it.copy(pieces = PieceCount.P96) }

        vm.openImage(image)
        advanceUntilIdle()

        val resumed = vm.game
        assertTrue(resumed.scattered)
        assertEquals(PieceCount.DEFAULT, resumed.pieces)
        assertEquals(old.cut.board.snapshot, resumed.cut.board.snapshot)
    }

    @Test
    fun `an untouched puzzle is cut afresh for a new piece count`() = runTest {
        val vm = viewModel()
        vm.openImage(image)
        advanceUntilIdle()
        val old = vm.game
        old.scatter()
        vm.back()
        advanceUntilIdle()
        assertInstanceOf(SavedPuzzle.InProgress::class.java, saved)
        vm.updateSettings { it.copy(pieces = PieceCount.P96) }

        vm.openImage(image)
        advanceUntilIdle()

        val recut = vm.game
        assertFalse(recut.scattered)
        assertEquals(PieceCount.P96, recut.pieces)
        assertNotEquals(old.cut.board.size, recut.cut.board.size)
    }

    @Test
    fun `a stored piece count this version does not offer loads as the default`() = runTest {
        // A fresh app (files and preferences) per value, as each one is moved into the new store only once.
        fun storedPieces(target: Int) = viewModel(
            TestApp(File(tmp, "$target")).apply {
                getSharedPreferences("settings", 0).edit().putInt("pieces", target).apply()
            },
        ).settings.pieces

        assertEquals(PieceCount.P96, storedPieces(PieceCount.P96.target))
        assertEquals(PieceCount.DEFAULT, storedPieces(37))
    }

    @Test
    fun `a new puzzle is cut into the grid its untouched gallery tile shows`() = runTest {
        // Renditions of one 4000 × 3916 original: their rounded aspects (1.020, 1.0215) straddle the
        // 3 / 4 column boundary of 12 pieces.
        sizes[image.image.thumbnail.location] = 255 to 250
        sizes[image.image.full.location] = 1471 to 1440
        val vm = viewModel()
        vm.updateSettings { it.copy(pieces = PieceCount.P12) }

        vm.openImage(image)
        advanceUntilIdle()

        // renderUntouched cuts the tile from the thumbnail, so it shows the thumbnail's grid.
        assertEquals(JigsawCut.gridFor(255, 250, PieceCount.P12), vm.game.cut.board.grid)
    }

    @Test
    fun `a new puzzle opens in its own grid when the thumbnail can't be downloaded`() = runTest {
        sizes[image.image.full.location] = 1471 to 1440
        fetch = { if (it == image.image.thumbnail) throw IOException("offline") else it.location.encodeToByteArray() }
        val vm = viewModel()
        vm.updateSettings { it.copy(pieces = PieceCount.P12) }

        vm.openImage(image)
        advanceUntilIdle()

        assertEquals(JigsawCut.gridFor(1471, 1440, PieceCount.P12), vm.game.cut.board.grid)
    }

    @Test
    fun `a picture that can't be downloaded fails the load`() = runTest {
        fetch = { throw IOException("offline") }
        val vm = viewModel()

        vm.openImage(image)
        advanceUntilIdle()

        assertInstanceOf(Load.Failed::class.java, vm.puzzle)
    }

    @Test
    fun `a failed save puts the tile back to the board last written`() = runTest {
        val vm = viewModel()
        vm.openImage(image)
        advanceUntilIdle()
        val game = vm.game
        game.scatter()
        vm.saveCurrentPuzzle()
        advanceUntilIdle()
        val written = saved
        assertInstanceOf(SavedPuzzle.InProgress::class.java, written)
        blockSaves()

        game.placeFirstPiece()
        vm.saveCurrentPuzzle()
        assertNotEquals(written, vm.tiles[image.puzzleKey])
        advanceUntilIdle()

        assertEquals(written, saved)
        assertEquals(written, vm.tiles[image.puzzleKey])
    }

    @Test
    fun `a failed first save leaves no tile`() = runTest {
        // The puzzles directory can't be created, so nothing ever reaches the disk.
        File(tmp, "puzzles").writeText("")
        val vm = viewModel()
        vm.openImage(image)
        advanceUntilIdle()
        vm.game.scatter()

        vm.saveCurrentPuzzle()
        assertInstanceOf(SavedPuzzle.InProgress::class.java, vm.tiles[image.puzzleKey])
        advanceUntilIdle()

        assertNull(saved)
        assertEquals(emptyMap<PuzzleKey, SavedPuzzle>(), vm.tiles)
    }

    @Test
    fun `a failed save does not undo the tile of a later save that lands`() = runTest {
        val vm = viewModel()
        vm.openImage(image)
        advanceUntilIdle()
        vm.game.scatter()
        vm.saveCurrentPuzzle()
        advanceUntilIdle()
        val block = blockSaves()
        vm.game.solve()
        // As on a device, a write finishing on the I/O thread posts back to the main thread; the test
        // drains those posts itself, so a second save can come in between.
        val looper = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(looper))

        vm.saveCurrentPuzzle()
        looper.runCurrent()
        runCurrent() // the write fails
        vm.back() // saves the solved board again
        block.delete()
        looper.runCurrent()
        runCurrent() // the second write lands
        looper.runCurrent()

        assertEquals(SavedPuzzle.Solved, saved)
        assertEquals(SavedPuzzle.Solved, vm.tiles[image.puzzleKey])
    }

    @Test
    fun `a failed save does not bring back a tile start over removed`() = runTest {
        val vm = viewModel()
        vm.openImage(image)
        advanceUntilIdle()
        val game = vm.game
        game.scatter()
        vm.saveCurrentPuzzle()
        advanceUntilIdle()
        blockSaves()

        game.placeFirstPiece()
        vm.saveCurrentPuzzle()
        vm.restartPuzzle()
        advanceUntilIdle()

        assertNull(saved)
        assertNull(vm.tiles[image.puzzleKey])
    }

    @Test
    fun `saves of stored sources this version drops are deleted, those of kept sources stay`() = runTest {
        val app = TestApp(tmp)
        val stored = """[
            {"type": "immich", "id": "src", "label": "Pictures", "url": "https://p/share/k"},
            {"type": "nextcloud", "id": "nc", "label": "NC", "url": "https://c/s/t", "password": "pw"},
            {"type": "urllist", "id": "list", "label": "List", "url": "https://e/l.txt"}
        ]"""
        app.getSharedPreferences("sources", 0).edit().putString("sources", stored).apply()
        val store = PuzzleStore(File(tmp, "puzzles"))
        val dropped = listOf(PuzzleKey("nc", "a.jpg"), PuzzleKey("list", "https://e/b.jpg"))
        for (key in dropped + image.puzzleKey) store.save(key, SavedPuzzle.Solved)

        val vm = viewModel(app)
        advanceUntilIdle()

        assertEquals(listOf(source), vm.sources)
        assertEquals(SavedPuzzle.Solved, saved)
        for (key in dropped) assertNull(store.load(key), key.toString())
        // Nothing is left of the dropped sources, not even their directories.
        assertEquals(1, File(tmp, "puzzles").list()!!.size)
    }

    @Test
    fun `sources and settings saved by earlier versions move to the new store once, then changes stick`() = runTest {
        val app = TestApp(tmp)
        // As an earlier version left them (Android's org.json escapes `/`).
        app.getSharedPreferences("sources", 0).edit()
            .putString("sources", """[{"type":"immich","id":"src","label":"Pictures","url":"https:\/\/p\/share\/k"}]""")
            .apply()
        app.getSharedPreferences("settings", 0).edit()
            .putBoolean("snapToBoard", true)
            .putBoolean("rotatePieces", true)
            .putInt("pieces", PieceCount.P96.target)
            .apply()
        val firstRun = CoroutineScope(Job())
        val migratedSettings = PuzzleSettings(snapToBoard = true, rotatePieces = true, pieces = PieceCount.P96)

        val migrated = viewModel(app, firstRun)

        assertEquals(listOf(source), migrated.sources)
        assertEquals(migratedSettings, migrated.settings)
        // Emptied once moved, so the next start doesn't move them again over newer changes.
        for (name in listOf("sources", "settings")) {
            assertEquals(emptyMap<String, Any>(), app.getSharedPreferences(name, 0).all, name)
        }

        migrated.updateSettings { it.copy(pieces = PieceCount.P12) }
        migrated.removeSource(source.id)
        advanceUntilIdle()
        firstRun.cancel()
        val restarted = viewModel(app)

        assertEquals(emptyList<SourceConfig>(), restarted.sources)
        assertEquals(migratedSettings.copy(pieces = PieceCount.P12), restarted.settings)
    }

    @Test
    fun `after a failed read, changing one setting keeps the other stored settings`() = runTest {
        val stored = PuzzleSettings(snapToBoard = true, pieces = PieceCount.P96)
        File(tmp, "datastore/config.json").apply {
            parentFile!!.mkdirs()
            writeText(AppConfigSerializer.encode(AppConfig(settings = stored)))
        }
        val firstRun = CoroutineScope(Job())
        val vm = viewModel(storeScope = firstRun, wrapStore = ::UnreadableStore)
        assertEquals(PuzzleSettings(), vm.settings)

        // As the Settings screen turns on rotation.
        vm.updateSettings { it.copy(rotatePieces = true) }
        advanceUntilIdle()
        firstRun.cancel()

        assertEquals(stored.copy(rotatePieces = true), viewModel().settings)
    }

    @Test
    fun `a failed read keeps the puzzle saves of the stored sources`() = runTest {
        File(tmp, "datastore/config.json").apply {
            parentFile!!.mkdirs()
            writeText(AppConfigSerializer.encode(AppConfig(sources = listOf(source))))
        }
        PuzzleStore(File(tmp, "puzzles")).save(image.puzzleKey, SavedPuzzle.Solved)

        val vm = viewModel(wrapStore = ::UnreadableStore)
        advanceUntilIdle()

        // Read as no sources, but not taken as all of them removed.
        assertEquals(emptyList<SourceConfig>(), vm.sources)
        assertEquals(SavedPuzzle.Solved, saved)
    }

    @Test
    fun `a failed read keeps the saved gallery listings of the stored sources`() = runTest {
        File(tmp, "datastore/config.json").apply {
            parentFile!!.mkdirs()
            writeText(AppConfigSerializer.encode(AppConfig(sources = listOf(source))))
        }
        val listings = ImageListStore(File(tmp, "gallery/listings.json"), StandardTestDispatcher(testScheduler))
        listings.save(mapOf(source.id to listOf(image.image)))

        val vm = viewModel(wrapStore = ::UnreadableStore)
        val visible = launch { vm.feed.keepFresh() }
        // Not advanceUntilIdle: keepFresh refreshes again every minute, forever.
        runCurrent()
        visible.cancel()

        // The gallery lists no sources, but not all of them are gone: the next start still shows these at once.
        assertEquals(mapOf(source.id to listOf(image.image)), listings.load())
    }

    @Test
    fun `a folder is added with its read access kept across restarts`() = runTest {
        val vm = viewModel()

        assertEquals(AddSourceResult.Added, vm.addSource(folder))

        assertEquals(listOf(folder.uri), keptFolders)
        assertEquals(listOf(folder), vm.sources)
    }

    @Test
    fun `a folder whose provider refuses to keep access is a denied grant, not an invalid folder`() = runTest {
        keepFolderAccess = { throw SecurityException("No persistable permission grants found") }
        val vm = viewModel()

        assertEquals(AddSourceResult.FolderAccessDenied, vm.addSource(folder))

        assertEquals(emptyList<SourceConfig>(), vm.sources)
    }

    @Test
    fun `a config that isn't a source of its kind is invalid, and no folder access is kept for it`() = runTest {
        openSource = { throw IllegalArgumentException("Not a folder tree URI") }
        val vm = viewModel()

        assertEquals(AddSourceResult.Invalid, vm.addSource(folder))

        assertEquals(emptyList<String>(), keptFolders)
        assertEquals(emptyList<SourceConfig>(), vm.sources)
    }

    @Test
    fun `a wrong-typed setting costs only that setting, and the next save repairs the file`() = runTest {
        val config = File(tmp, "datastore/config.json").apply { parentFile!!.mkdirs() }
        val stored = AppConfig(sources = listOf(source), settings = PuzzleSettings(pieces = PieceCount.P96))
        val unparseable = AppConfigSerializer.encode(stored)
            .replace("\"snapToBoard\":false", "\"snapToBoard\":\"yes\"")
        check(unparseable.contains("\"yes\""))
        config.writeText(unparseable)
        PuzzleStore(File(tmp, "puzzles")).save(image.puzzleKey, SavedPuzzle.Solved)

        val firstRun = CoroutineScope(Job())
        val vm = viewModel(storeScope = firstRun)
        assertEquals(stored.sources, vm.sources)
        assertEquals(stored.settings, vm.settings)
        assertEquals(SavedPuzzle.Solved, saved)

        vm.updateSettings { it.copy(rotatePieces = true) }
        advanceUntilIdle()
        firstRun.cancel()

        val restarted = viewModel()
        assertEquals(stored.sources, restarted.sources)
        assertEquals(stored.settings.copy(rotatePieces = true), restarted.settings)
        assertEquals(SavedPuzzle.Solved, saved)
    }

    @Test
    fun `a config that isn't JSON reads as nothing configured, and the next save replaces it`() = runTest {
        File(tmp, "datastore/config.json").apply {
            parentFile!!.mkdirs()
            writeText("{\"sources\": [")
        }

        val firstRun = CoroutineScope(Job())
        val vm = viewModel(storeScope = firstRun)
        assertEquals(emptyList<SourceConfig>(), vm.sources)
        vm.updateSettings { it.copy(rotatePieces = true) }
        advanceUntilIdle()
        firstRun.cancel()

        assertEquals(PuzzleSettings(rotatePieces = true), viewModel().settings)
    }

    @Test
    fun `object- or array-valued settings read as their defaults`() = runTest {
        val stored = AppConfig(sources = listOf(source), settings = PuzzleSettings(rotatePieces = true))
        File(tmp, "datastore/config.json").apply {
            parentFile!!.mkdirs()
            writeText(
                AppConfigSerializer.encode(stored)
                    .replace("\"snapToBoard\":false", "\"snapToBoard\":{}")
                    .replace(Regex("\"pieces\":\\d+"), "\"pieces\":{\"rows\":4}"),
            )
        }
        PuzzleStore(File(tmp, "puzzles")).save(image.puzzleKey, SavedPuzzle.Solved)

        val vm = viewModel()
        assertEquals(stored.sources, vm.sources)
        assertEquals(stored.settings, vm.settings)
        assertEquals(SavedPuzzle.Solved, saved)
    }

    /** A config store whose reads fail; writes reach [store]. */
    private class UnreadableStore(private val store: DataStore<AppConfig>) : DataStore<AppConfig> {
        override val data: Flow<AppConfig> = flow { throw IOException("Unreadable") }

        override suspend fun updateData(transform: suspend (t: AppConfig) -> AppConfig) = store.updateData(transform)
    }

    private object NoImages : ImageSource {
        override suspend fun listImages() = emptyList<RemoteImage>()
    }

    /** A [width] × [height] picture whose pieces have no pixels: android.graphics only works on a device. */
    private inner class FakePicture(override val width: Int = 1200, override val height: Int = 800) : PuzzlePicture {
        override fun asImageBitmap(): ImageBitmap = FakeImageBitmap(width, height)

        override fun cut(cut: JigsawCut, board: PuzzleBoard) = CutPuzzle(
            source = asImageBitmap(),
            board = board,
            pieces = emptyList(),
            cellWidth = width / cut.cols.toFloat(),
            cellHeight = height / cut.rows.toFloat(),
        )
    }
}

internal class FakeImageBitmap(override val width: Int, override val height: Int) : ImageBitmap {
    override val colorSpace get() = ColorSpaces.Srgb
    override val hasAlpha get() = false
    override val config get() = ImageBitmapConfig.Argb8888

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) = throw UnsupportedOperationException("No pixels in tests")

    override fun prepareToDraw() = Unit
}

/** The parts of an [Application] the view model uses: its files directory and preferences. */
private class TestApp(private val files: File) : Application() {
    private val preferences = mutableMapOf<String, SharedPreferences>()

    override fun getFilesDir() = files

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferences.getOrPut(name, ::MemoryPreferences)

    /** Where DataStore deletes moved preferences files: Build.VERSION.SDK_INT is 0 in JVM tests. */
    override fun getApplicationInfo() = ApplicationInfo().apply { dataDir = files.path }
}

/** In-memory [SharedPreferences]; android.jar only has stubs in JVM tests. */
private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any>()

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as Set<String>? ?: defValues

    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue

    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue

    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue

    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue

    override fun contains(key: String) = key in values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = throw UnsupportedOperationException()

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = throw UnsupportedOperationException()

    private inner class Editor : SharedPreferences.Editor {
        /** Null removes the key. */
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = put(key, value)

        override fun putStringSet(key: String, values: Set<String>?) = put(key, values)

        override fun putInt(key: String, value: Int) = put(key, value)

        override fun putLong(key: String, value: Long) = put(key, value)

        override fun putFloat(key: String, value: Float) = put(key, value)

        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String) = put(key, null)

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            changes[key] = value
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) values.clear()
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
