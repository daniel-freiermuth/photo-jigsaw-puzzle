package dev.daniel.jigsaw.ui

import android.graphics.Region
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.daniel.jigsaw.puzzle.BoardLayout
import dev.daniel.jigsaw.puzzle.BoardSnapshot
import dev.daniel.jigsaw.puzzle.Bounds
import dev.daniel.jigsaw.puzzle.CutPuzzle
import dev.daniel.jigsaw.puzzle.Grid
import dev.daniel.jigsaw.puzzle.JigsawCut
import dev.daniel.jigsaw.puzzle.PieceCount
import dev.daniel.jigsaw.puzzle.PieceImage
import dev.daniel.jigsaw.puzzle.Pt
import dev.daniel.jigsaw.puzzle.PuzzleBoard
import dev.daniel.jigsaw.source.HttpResource
import dev.daniel.jigsaw.source.RemoteImage
import dev.daniel.jigsaw.source.SourceConfig
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.createCoroutine
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * dragGroups (PuzzleScreen's drag handling) fed through the real detectDragGestures by [PointerInputHost], which
 * starts and dispatches to it the way Compose does. Two loose pieces, one cell (100 px) each, board origin at
 * (100, 100): [PIECE] covers canvas (300..400, 300..400), [CORNER] covers (0..100, 0..100).
 */
class PuzzleGesturesTest {
    private val board = PuzzleBoard(
        Grid(rows = 1, cols = 2),
        BoardSnapshot(
            xs = listOf(2f, -1f),
            ys = listOf(2f, -1f),
            groups = listOf(0, 1),
            locked = listOf(false, false),
            order = listOf(0, 1),
        ),
    )
    private val game = PuzzleGame(
        image = GalleryImage(
            SourceConfig.UrlList("src", "Pictures", "https://p/list.txt"),
            RemoteImage("a.jpg", "a", HttpResource("https://p/thumb/a.jpg"), HttpResource("https://p/a.jpg")),
        ),
        seed = 0,
        pieces = PieceCount.DEFAULT,
        cut = CutPuzzle(
            source = FakeImageBitmap(CELL * 2, CELL),
            board = board,
            pieces = List(board.size) { cellPiece() },
            cellWidth = CELL.toFloat(),
            cellHeight = CELL.toFloat(),
        ),
        random = Random(0),
        scattered = true,
    )
    private val layout = BoardLayout(
        origin = Pt(ORIGIN, ORIGIN),
        scale = 1f,
        cellSize = Pt(CELL.toFloat(), CELL.toFloat()),
        screen = Bounds(-1f, -1f, CANVAS / CELL - 1f, CANVAS / CELL - 1f),
        scatter = Bounds(-1f, -1f, CANVAS / CELL - 1f, CANVAS / CELL - 1f),
    )
    private val settled = mutableListOf<Int>()

    private fun TestScope.freshPointerInput() =
        PointerInputHost(this) { dragGroups(game, mutableStateOf(layout), settled::add) }

    @Test
    fun `the first drag of a fresh pointer input picks up the touched piece`() = runTest {
        // One move straight past the slop: the touch is the only place inside the piece.
        freshPointerInput().drag(from = Offset(350f, 350f), to = listOf(Offset(550f, 350f)))

        assertEquals(4f, board.x(PIECE), 1e-4f)
        assertEquals(2f, board.y(PIECE), 1e-4f)
        assertEquals(listOf(PIECE), settled)
    }

    @Test
    fun `a dragged piece follows the whole finger travel from the touch, not from where the slop was crossed`() =
        runTest {
            val path = (1..40).map { Offset(350f + it * 5f, 350f - it * 2.5f) }
            freshPointerInput().drag(from = Offset(350f, 350f), to = path)

            assertEquals(4f, board.x(PIECE), 1e-4f)
            assertEquals(1f, board.y(PIECE), 1e-4f)
            assertEquals(listOf(PIECE), settled)
        }

    @Test
    fun `a drag from empty canvas leaves the group at the canvas corner alone`() = runTest {
        freshPointerInput().drag(from = Offset(700f, 700f), to = (1..20).map { Offset(700f - it * 10f, 700f) })

        assertEquals(-1f, board.x(CORNER), 1e-4f)
        assertEquals(-1f, board.y(CORNER), 1e-4f)
        assertEquals(2f, board.x(PIECE), 1e-4f)
        assertEquals(emptyList<Int>(), settled)
    }

    /** A piece whose hit region is exactly its cell (inside the tab padding around it). */
    private fun cellPiece(): PieceImage {
        val pad = (JigsawCut.PAD * CELL).toInt()
        val side = CELL + 2 * pad
        val image = FakeImageBitmap(side, side)
        return PieceImage(image, image, Region(pad, pad, pad + CELL, pad + CELL))
    }

    private companion object {
        const val CELL = 100
        const val ORIGIN = 100f
        const val CANVAS = 1000f
        const val PIECE = 0
        const val CORNER = 1
    }
}

/**
 * Hosts a pointerInput block like Compose's SuspendingPointerInputModifierNodeImpl: the block starts undispatched
 * on the first event, which then goes pass by pass (Main in reverse) to the awaitPointerEventScope handlers
 * registered at that moment; handlers resume undispatched, while anything the block launches waits for the test
 * dispatcher, which runs between events as the main looper does.
 */
private class PointerInputHost(
    private val test: TestScope,
    private val block: suspend PointerInputScope.() -> Unit,
) : PointerInputScope, Density by Density(1f) {
    override val size = IntSize(CANVAS_PX, CANVAS_PX)
    override val viewConfiguration = object : ViewConfiguration {
        override val longPressTimeoutMillis = 400L
        override val doubleTapTimeoutMillis = 300L
        override val doubleTapMinTimeMillis = 40L
        override val touchSlop = 8f
    }

    private val handlers = mutableListOf<Handler<*>>()
    private var job: Job? = null
    private var currentEvent = PointerEvent(emptyList())
    private val finger = PointerId(0)
    private var position = Offset.Zero
    private var pressed = false
    private var time = 0L

    /** One finger touches [from], moves through [to] and lifts at its end. */
    fun drag(from: Offset, to: List<Offset>) {
        send(from, down = true)
        to.forEach { send(it, down = true) }
        send(to.last(), down = false)
    }

    private fun send(at: Offset, down: Boolean) {
        val change = PointerInputChange(
            id = finger,
            uptimeMillis = time + FRAME_MILLIS,
            position = at,
            pressed = down,
            previousUptimeMillis = time,
            previousPosition = if (pressed) position else at,
            previousPressed = pressed,
            isInitiallyConsumed = false,
        )
        time += FRAME_MILLIS
        position = at
        pressed = down
        val event = PointerEvent(listOf(change))
        currentEvent = event
        if (job == null) job = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { block() }
        for (pass in PointerEventPass.entries) {
            val current = handlers.toList()
            (if (pass == PointerEventPass.Main) current.asReversed() else current).forEach { it.offer(event, pass) }
        }
        test.testScheduler.runCurrent()
    }

    override suspend fun <R> awaitPointerEventScope(block: suspend AwaitPointerEventScope.() -> R): R =
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(continuation)
            handlers += handler
            block.createCoroutine(handler, handler).resume(Unit)
            continuation.invokeOnCancellation { handler.cancel(it) }
        }

    private inner class Handler<R>(private val completion: Continuation<R>) :
        AwaitPointerEventScope,
        Density by this@PointerInputHost,
        Continuation<R> {
        private var awaiter: CancellableContinuation<PointerEvent>? = null
        private var awaitPass = PointerEventPass.Main

        override val currentEvent get() = this@PointerInputHost.currentEvent
        override val size get() = this@PointerInputHost.size
        override val viewConfiguration get() = this@PointerInputHost.viewConfiguration
        override val context = EmptyCoroutineContext

        fun offer(event: PointerEvent, pass: PointerEventPass) {
            if (pass != awaitPass) return
            awaiter?.let {
                awaiter = null
                it.resume(event)
            }
        }

        fun cancel(cause: Throwable?) {
            awaiter?.cancel(cause)
            awaiter = null
        }

        override fun resumeWith(result: Result<R>) {
            handlers -= this
            completion.resumeWith(result)
        }

        override suspend fun awaitPointerEvent(pass: PointerEventPass): PointerEvent =
            suspendCancellableCoroutine {
                awaitPass = pass
                awaiter = it
            }
    }

    private companion object {
        const val CANVAS_PX = 1000
        const val FRAME_MILLIS = 16L
    }
}
