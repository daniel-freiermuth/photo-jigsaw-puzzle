package dev.daniel.jigsaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.daniel.jigsaw.puzzle.Bounds
import dev.daniel.jigsaw.puzzle.CutPuzzle
import dev.daniel.jigsaw.puzzle.JigsawCut
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Fraction of the long screen axis given to the board; the rest is the tray for loose pieces. */
private const val BOARD_SHARE = 0.55f
private const val BOARD_FILL_ALPHA = 0.06f
private const val BOARD_STROKE_ALPHA = 0.35f
private const val PIECE_OUTLINE_ALPHA = 0.5f
private const val HINT_ALPHA = 0.3f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PuzzleScreen(
    title: String,
    state: Load<PuzzleContent>,
    onBack: () -> Unit,
    onAnotherImage: () -> Unit,
    onPlayAgain: () -> Unit,
    onMoveEnd: () -> Unit,
    snapToBoard: Boolean,
    modifier: Modifier = Modifier,
) {
    val content = (state as? Load.Ready)?.value
    Scaffold(
        modifier = modifier,
        topBar = {
            BackTopBar(title, onBack) {
                when (content) {
                    is PuzzleContent.Playing -> {
                        val game = content.game
                        TextButton(onClick = { game.showHint = !game.showHint }) {
                            Text(if (game.showHint) "Hide hint" else "Hint")
                        }
                    }
                    is PuzzleContent.Finished -> TextButton(onClick = onPlayAgain) { Text("Play again") }
                    null -> Unit
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            when (state) {
                Load.Loading -> CircularProgressIndicator()
                is Load.Failed -> Text("Couldn't load image:\n${state.message}", Modifier.padding(24.dp))
                is Load.Ready -> when (val value = state.value) {
                    is PuzzleContent.Finished -> Image(
                        value.picture,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )
                    is PuzzleContent.Playing -> {
                        PuzzleBoardView(value.game, snapToBoard, onMoveEnd)
                        if (value.game.solved) {
                            SolvedCard(onBack, onAnotherImage, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SolvedCard(onBack: () -> Unit, onAnotherImage: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.padding(24.dp)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Solved!", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack) { Text("Gallery") }
                Button(onClick = onAnotherImage) { Text("Another image") }
            }
        }
    }
}

/** Screen placement of the board plus the grid-unit areas pieces may occupy. */
private class BoardLayout(
    /** Screen position of the board's top-left corner. */
    val origin: Offset,
    /** Screen pixels per source-bitmap pixel. */
    val scale: Float,
    /** On-screen size of one grid cell. */
    val cellPx: Size,
    /** The whole canvas; groups must keep a grabbable part inside it. */
    val screen: Bounds,
    /** Where pieces start out, kept clear of the screen edges so tabs aren't clipped. */
    val scatter: Bounds,
)

private class PuzzleColors(val boardFill: Color, val boardStroke: Color, val pieceOutline: ColorFilter)

/** Portrait: board on top, loose pieces below. Landscape: board left, loose pieces right. */
private fun computeLayout(size: IntSize, cut: CutPuzzle, margin: Float): BoardLayout {
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val iw = cut.source.width.toFloat()
    val ih = cut.source.height.toFloat()
    val portrait = h >= w
    val scale = if (portrait) {
        min((w - 2 * margin) / iw, (h * BOARD_SHARE - margin) / ih)
    } else {
        min((w * BOARD_SHARE - margin) / iw, (h - 2 * margin) / ih)
    }
    val origin = if (portrait) Offset((w - iw * scale) / 2, margin) else Offset(margin, (h - ih * scale) / 2)
    val cellPx = Size(cut.cellWidth * scale, cut.cellHeight * scale)
    fun toGrid(left: Float, top: Float, right: Float, bottom: Float, inset: Float) = Bounds(
        minX = (left - origin.x) / cellPx.width + inset,
        minY = (top - origin.y) / cellPx.height + inset,
        maxX = (right - origin.x) / cellPx.width - inset,
        maxY = (bottom - origin.y) / cellPx.height - inset,
    )
    val pad = JigsawCut.PAD
    val tray = if (portrait) {
        toGrid(0f, origin.y + ih * scale + margin, w, h, pad)
    } else {
        toGrid(origin.x + iw * scale + margin, 0f, w, h, pad)
    }
    return BoardLayout(
        origin = origin,
        scale = scale,
        cellPx = cellPx,
        screen = toGrid(0f, 0f, w, h, inset = 0f),
        scatter = if (tray.fitsCell) tray else toGrid(0f, 0f, w, h, pad),
    )
}

@Composable
private fun PuzzleBoardView(
    game: PuzzleGame,
    snapToBoard: Boolean,
    onMoveEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val margin = with(LocalDensity.current) { 12.dp.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(canvasSize, game) {
        if (canvasSize == IntSize.Zero) return@remember null
        computeLayout(canvasSize, game.cut, margin).also {
            if (!game.scattered) {
                game.cut.board.scatter(it.scatter, Random.Default)
                game.scattered = true
            }
        }
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val colors = PuzzleColors(
        boardFill = onSurface.copy(alpha = BOARD_FILL_ALPHA),
        boardStroke = onSurface.copy(alpha = BOARD_STROKE_ALPHA),
        // onSurface is dark in light mode and light in dark mode, so edges stay visible on either background.
        pieceOutline = ColorFilter.tint(onSurface.copy(alpha = PIECE_OUTLINE_ALPHA)),
    )
    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .puzzleGestures(
                game = game,
                layout = rememberUpdatedState(layout),
                snapToBoard = rememberUpdatedState(snapToBoard),
                haptics = LocalHapticFeedback.current,
                onMoveEnd = rememberUpdatedState(onMoveEnd),
            ),
    ) {
        game.version // subscribe: redraw whenever the board changes
        layout?.let { drawPuzzle(game, it, colors) }
    }
}

/** Drag handling: pick up the touched group, move it, then resolve the drop on release. */
private fun Modifier.puzzleGestures(
    game: PuzzleGame,
    layout: State<BoardLayout?>,
    snapToBoard: State<Boolean>,
    haptics: HapticFeedback,
    onMoveEnd: State<() -> Unit>,
): Modifier = pointerInput(game) {
    val board = game.cut.board
    var dragging: Int? = null
    fun finish() {
        val i = dragging ?: return
        dragging = null
        layout.value?.let { board.keepGroupReachable(i, it.screen) }
        if (board.drop(i, snapToBoard.value)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (board.isSolved) game.solved = true
        game.version++
        onMoveEnd.value()
    }
    detectDragGestures(
        onDragStart = { pos ->
            dragging = layout.value?.let { hitTest(game.cut, it, pos) }?.also {
                board.bringToFront(it)
                game.version++
            }
        },
        onDragEnd = { finish() },
        onDragCancel = { finish() },
        onDrag = { change, amount ->
            val i = dragging
            val l = layout.value
            if (i != null && l != null) {
                change.consume()
                board.moveGroup(i, amount.x / l.cellPx.width, amount.y / l.cellPx.height)
                game.version++
            }
        },
    )
}

private fun DrawScope.drawPuzzle(game: PuzzleGame, layout: BoardLayout, colors: PuzzleColors) {
    val cut = game.cut
    val board = cut.board
    val boardSize = Size(cut.source.width * layout.scale, cut.source.height * layout.scale)
    drawRect(colors.boardFill, layout.origin, boardSize)
    drawRect(colors.boardStroke, layout.origin, boardSize, style = Stroke(width = 1.dp.toPx()))
    if (game.showHint) {
        drawImage(
            cut.source,
            dstOffset = layout.origin.round(),
            dstSize = IntSize(boardSize.width.roundToInt(), boardSize.height.roundToInt()),
            alpha = HINT_ALPHA,
        )
    }
    for (i in board.drawOrder) {
        val piece = cut.pieces[i]
        val topLeft = layout.origin + Offset(
            (board.x(i) * cut.cellWidth - cut.padX) * layout.scale,
            (board.y(i) * cut.cellHeight - cut.padY) * layout.scale,
        )
        val dstSize = IntSize(
            (piece.image.width * layout.scale).roundToInt(),
            (piece.image.height * layout.scale).roundToInt(),
        )
        drawImage(piece.image, dstOffset = topLeft.round(), dstSize = dstSize)
        drawImage(piece.outline, dstOffset = topLeft.round(), dstSize = dstSize, colorFilter = colors.pieceOutline)
    }
}

private fun Offset.round() = IntOffset(x.roundToInt(), y.roundToInt())

/** Topmost loose piece whose actual (tabbed) shape contains the screen point. */
private fun hitTest(cut: CutPuzzle, layout: BoardLayout, pos: Offset): Int? {
    val sx = (pos.x - layout.origin.x) / layout.scale
    val sy = (pos.y - layout.origin.y) / layout.scale
    return cut.board.pieceAt { i ->
        val lx = sx - (cut.board.x(i) * cut.cellWidth - cut.padX)
        val ly = sy - (cut.board.y(i) * cut.cellHeight - cut.padY)
        cut.pieces[i].region.contains(lx.toInt(), ly.toInt())
    }
}
