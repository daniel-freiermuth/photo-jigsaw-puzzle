package dev.daniel.jigsaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.daniel.jigsaw.R
import dev.daniel.jigsaw.puzzle.BoardLayout
import dev.daniel.jigsaw.puzzle.CanvasSpec
import dev.daniel.jigsaw.puzzle.CutPuzzle
import dev.daniel.jigsaw.puzzle.boardLayout
import dev.daniel.jigsaw.puzzle.keepAllReachable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    onStartOver: () -> Unit,
    onMoveEnd: () -> Unit,
    snapToBoard: Boolean,
    modifier: Modifier = Modifier,
) {
    val content = (state as? Load.Ready)?.value
    Scaffold(
        modifier = modifier,
        topBar = {
            BackTopBar(title, onBack) { PuzzleActions(content, onStartOver) }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            when (state) {
                Load.Loading -> CircularProgressIndicator()

                is Load.Failed -> Text(
                    stringResource(R.string.puzzle_load_failed) + "\n" + state.reason.resolve(),
                    Modifier.padding(24.dp),
                )

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

/** Top-bar actions: hint toggle and a menu with "Start over" while playing, "Play again" once solved. */
@Composable
private fun PuzzleActions(content: PuzzleContent?, onStartOver: () -> Unit) {
    when (content) {
        is PuzzleContent.Playing -> PlayingActions(content.game, onStartOver)

        // Solved: nothing to lose, so no confirmation.
        is PuzzleContent.Finished -> TextButton(
            onClick = onStartOver,
        ) { Text(stringResource(R.string.puzzle_play_again)) }

        null -> Unit
    }
}

@Composable
private fun PlayingActions(game: PuzzleGame, onStartOver: () -> Unit, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmStartOver by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { game.showHint = !game.showHint }) {
            Text(stringResource(if (game.showHint) R.string.puzzle_hide_hint else R.string.puzzle_hint))
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.puzzle_more_options),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.puzzle_start_over)) },
                    onClick = {
                        menuOpen = false
                        confirmStartOver = true
                    },
                )
            }
        }
        if (confirmStartOver) {
            AlertDialog(
                onDismissRequest = { confirmStartOver = false },
                title = { Text(stringResource(R.string.puzzle_start_over_title)) },
                text = { Text(stringResource(R.string.puzzle_start_over_text)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmStartOver = false
                            onStartOver()
                        },
                    ) { Text(stringResource(R.string.puzzle_start_over)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmStartOver = false }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
    }
}

@Composable
private fun SolvedCard(onBack: () -> Unit, onAnotherImage: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.padding(24.dp)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.puzzle_solved), style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.puzzle_gallery)) }
                Button(onClick = onAnotherImage) { Text(stringResource(R.string.puzzle_another_image)) }
            }
        }
    }
}

private class PuzzleColors(val boardFill: Color, val boardStroke: Color, val pieceOutline: ColorFilter)

/** Margin kept between the board and the canvas edges. */
private val BoardMargin = 12.dp

/** Height of the puzzle screen's top bar (Material 3 small top app bar). */
private val TopBarHeight = 64.dp

/**
 * The puzzle canvas size on this device, derived from the window: what the puzzle screen gets below
 * its top bar and inside the system bars. Lets other screens lay pieces out exactly as a puzzle would.
 */
@Composable
fun puzzleCanvasSpec(): CanvasSpec {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val bars = WindowInsets.systemBars
    return with(density) {
        CanvasSpec(
            width = window.width.toFloat(),
            height = (window.height - bars.getTop(density) - bars.getBottom(density) - TopBarHeight.roundToPx())
                .toFloat(),
            margin = BoardMargin.toPx(),
        )
    }
}

private val BoardLayout.originOffset get() = Offset(origin.x, origin.y)

@Composable
private fun PuzzleBoardView(
    game: PuzzleGame,
    snapToBoard: Boolean,
    onMoveEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val margin = with(LocalDensity.current) { BoardMargin.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(canvasSize, game) {
        if (canvasSize == IntSize.Zero) return@remember null
        val canvas = CanvasSpec(canvasSize.width.toFloat(), canvasSize.height.toFloat(), margin)
        val board = game.cut.board
        boardLayout(canvas, game.cut.source.width.toFloat(), game.cut.source.height.toFloat(), board.rows, board.cols)
            .also {
                if (!game.scattered) {
                    board.scatter(it.scatter, game.random)
                    game.scattered = true
                } else {
                    // A new canvas shape (rotation, resize, resuming in the other orientation) moves the
                    // tray; move groups left on the old one into the new one.
                    board.keepAllReachable(it.screen, it.scatter, game.random)
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

/**
 * Drag handling: pick up the touched group, move it, then resolve the drop on release. On rotatable
 * boards a tap turns the tapped group a quarter turn clockwise.
 */
private fun Modifier.puzzleGestures(
    game: PuzzleGame,
    layout: State<BoardLayout?>,
    snapToBoard: State<Boolean>,
    haptics: HapticFeedback,
    onMoveEnd: State<() -> Unit>,
): Modifier {
    val board = game.cut.board

    /** After any move: keep the group reachable, join neighbours, maybe solve, then persist. */
    fun settle(i: Int) {
        layout.value?.let { board.keepGroupReachable(i, it.screen) }
        if (board.drop(i, snapToBoard.value)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (board.isSolved) game.solved = true
        game.version++
        onMoveEnd.value()
    }
    return pointerInput(game) {
        var dragging: Int? = null
        var downAt = Offset.Zero
        fun finish() {
            dragging?.let(::settle)
            dragging = null
        }
        coroutineScope {
            // detectDragGestures reports where the touch slop was crossed (~8dp from the touch); remember the touch.
            launch { awaitEachGesture { downAt = awaitFirstDown(requireUnconsumed = false).position } }
            detectDragGestures(
                onDragStart = { _ ->
                    dragging = layout.value?.let { hitTest(game.cut, it, downAt) }?.also {
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
                        board.moveGroup(i, amount.x / l.cellSize.x, amount.y / l.cellSize.y)
                        game.version++
                    }
                },
            )
        }
    }.pointerInput(game) {
        detectTapGestures { pos ->
            if (!board.rotatable) return@detectTapGestures
            layout.value?.let { hitTest(game.cut, it, pos) }?.let {
                board.bringToFront(it)
                board.rotateGroup(it)
                settle(it)
            }
        }
    }
}

private fun DrawScope.drawPuzzle(game: PuzzleGame, layout: BoardLayout, colors: PuzzleColors) {
    val cut = game.cut
    val board = cut.board
    val boardSize = Size(cut.source.width * layout.scale, cut.source.height * layout.scale)
    drawRect(colors.boardFill, layout.originOffset, boardSize)
    drawRect(colors.boardStroke, layout.originOffset, boardSize, style = Stroke(width = 1.dp.toPx()))
    if (game.showHint) {
        drawImage(
            cut.source,
            dstOffset = layout.originOffset.round(),
            dstSize = IntSize(boardSize.width.roundToInt(), boardSize.height.roundToInt()),
            alpha = HINT_ALPHA,
        )
    }
    for (i in board.drawOrder) {
        val piece = cut.pieces[i]
        val topLeft = layout.originOffset + Offset(
            (board.x(i) * cut.cellWidth - cut.padX) * layout.scale,
            (board.y(i) * cut.cellHeight - cut.padY) * layout.scale,
        )
        val center = layout.originOffset + Offset(
            (board.x(i) + HALF) * cut.cellWidth * layout.scale,
            (board.y(i) + HALF) * cut.cellHeight * layout.scale,
        )
        val dstSize = IntSize(
            (piece.image.width * layout.scale).roundToInt(),
            (piece.image.height * layout.scale).roundToInt(),
        )
        rotate(degrees = board.turns(i) * QUARTER_TURN_DEGREES, pivot = center) {
            drawImage(piece.image, dstOffset = topLeft.round(), dstSize = dstSize)
            drawImage(piece.outline, dstOffset = topLeft.round(), dstSize = dstSize, colorFilter = colors.pieceOutline)
        }
    }
}

private fun Offset.round() = IntOffset(x.roundToInt(), y.roundToInt())

/** Topmost loose piece whose actual (tabbed, turned) shape contains the screen point. */
private fun hitTest(cut: CutPuzzle, layout: BoardLayout, pos: Offset): Int? {
    val sx = (pos.x - layout.origin.x) / layout.scale
    val sy = (pos.y - layout.origin.y) / layout.scale
    val board = cut.board
    return board.pieceAt { i ->
        // Offset from the piece centre, turned back to the piece's upright frame (counter-clockwise).
        var dx = sx - (board.x(i) + HALF) * cut.cellWidth
        var dy = sy - (board.y(i) + HALF) * cut.cellHeight
        repeat(board.turns(i)) {
            val nx = dy
            dy = -dx
            dx = nx
        }
        val lx = dx + cut.padX + cut.cellWidth * HALF
        val ly = dy + cut.padY + cut.cellHeight * HALF
        cut.pieces[i].region.contains(lx.toInt(), ly.toInt())
    }
}

private const val HALF = 0.5f
private const val QUARTER_TURN_DEGREES = 90f
