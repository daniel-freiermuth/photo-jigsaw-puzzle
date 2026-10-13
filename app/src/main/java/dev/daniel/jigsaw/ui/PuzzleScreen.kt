package dev.daniel.jigsaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.daniel.jigsaw.puzzle.CutPuzzle
import dev.daniel.jigsaw.puzzle.JigsawCut
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PuzzleScreen(
    title: String,
    state: Load<PuzzleGame>,
    onBack: () -> Unit,
    onAnotherImage: () -> Unit,
) {
    val game = (state as? Load.Ready)?.value
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (game != null) {
                        TextButton(onClick = { game.showHint = !game.showHint }) {
                            Text(if (game.showHint) "Hide hint" else "Hint")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            when (state) {
                Load.Loading -> CircularProgressIndicator()
                is Load.Failed -> Text("Couldn't load image:\n${state.message}", Modifier.padding(24.dp))
                is Load.Ready -> {
                    PuzzleBoardView(state.value)
                    if (state.value.solved) SolvedCard(onBack, onAnotherImage, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

@Composable
private fun SolvedCard(onBack: () -> Unit, onAnotherImage: () -> Unit, modifier: Modifier) {
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
    val originX: Float,
    val originY: Float,
    /** Screen pixels per source-bitmap pixel. */
    val scale: Float,
    val cellPxW: Float,
    val cellPxH: Float,
    val visible: GridRect,
    val scatter: GridRect,
)

private data class GridRect(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val fitsPiece get() = maxX - minX >= 1f && maxY - minY >= 1f
}

/** Portrait: board on top, loose pieces below. Landscape: board left, loose pieces right. */
private fun computeLayout(size: IntSize, cut: CutPuzzle, margin: Float): BoardLayout {
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val iw = cut.source.width.toFloat()
    val ih = cut.source.height.toFloat()
    val portrait = h >= w
    val scale = if (portrait) min((w - 2 * margin) / iw, (h * 0.55f - margin) / ih)
    else min((w * 0.55f - margin) / iw, (h - 2 * margin) / ih)
    val ox = if (portrait) (w - iw * scale) / 2 else margin
    val oy = if (portrait) margin else (h - ih * scale) / 2
    val cellPxW = cut.cellWidth * scale
    val cellPxH = cut.cellHeight * scale
    val pad = JigsawCut.PAD
    fun toGrid(left: Float, top: Float, right: Float, bottom: Float) = GridRect(
        minX = (left - ox) / cellPxW + pad,
        minY = (top - oy) / cellPxH + pad,
        maxX = (right - ox) / cellPxW - pad,
        maxY = (bottom - oy) / cellPxH - pad,
    )
    val visible = toGrid(0f, 0f, w, h)
    val tray = if (portrait) toGrid(0f, oy + ih * scale + margin, w, h)
    else toGrid(ox + iw * scale + margin, 0f, w, h)
    return BoardLayout(ox, oy, scale, cellPxW, cellPxH, visible, if (tray.fitsPiece) tray else visible)
}

@Composable
private fun PuzzleBoardView(game: PuzzleGame) {
    val cut = game.cut
    val board = cut.board
    val haptics = LocalHapticFeedback.current
    val margin = with(LocalDensity.current) { 12.dp.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(canvasSize, game) {
        if (canvasSize == IntSize.Zero) return@remember null
        computeLayout(canvasSize, cut, margin).also {
            if (!game.scattered) {
                with(it.scatter) { board.scatter(minX, minY, maxX, maxY, Random.Default) }
                game.scattered = true
            }
        }
    }
    val currentLayout by rememberUpdatedState(layout)

    val boardColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val boardStroke = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(game) {
                var dragging: Int? = null
                fun finish() {
                    val i = dragging ?: return
                    val l = currentLayout ?: return
                    dragging = null
                    with(l.visible) { board.keepGroupInside(i, minX, minY, maxX, maxY) }
                    if (board.drop(i)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (board.isSolved) game.solved = true
                    game.version++
                }
                detectDragGestures(
                    onDragStart = { pos ->
                        val l = currentLayout ?: return@detectDragGestures
                        dragging = hitTest(cut, l, pos)?.also {
                            board.bringToFront(it)
                            game.version++
                        }
                    },
                    onDragEnd = { finish() },
                    onDragCancel = { finish() },
                    onDrag = { change, amount ->
                        val i = dragging ?: return@detectDragGestures
                        val l = currentLayout ?: return@detectDragGestures
                        change.consume()
                        board.moveGroup(i, amount.x / l.cellPxW, amount.y / l.cellPxH)
                        game.version++
                    },
                )
            },
    ) {
        val l = layout ?: return@Canvas
        game.version // subscribe: redraw whenever the board changes
        val boardTopLeft = Offset(l.originX, l.originY)
        val boardSize = Size(cut.source.width * l.scale, cut.source.height * l.scale)
        drawRect(boardColor, boardTopLeft, boardSize)
        drawRect(boardStroke, boardTopLeft, boardSize, style = Stroke(width = 1.dp.toPx()))
        if (game.showHint) {
            drawImage(
                cut.source,
                dstOffset = IntOffset(l.originX.roundToInt(), l.originY.roundToInt()),
                dstSize = IntSize(boardSize.width.roundToInt(), boardSize.height.roundToInt()),
                alpha = 0.3f,
            )
        }
        for (i in board.drawOrder) {
            val image = cut.pieces[i].image
            val left = l.originX + (board.x(i) * cut.cellWidth - cut.padX) * l.scale
            val top = l.originY + (board.y(i) * cut.cellHeight - cut.padY) * l.scale
            drawImage(
                image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize((image.width * l.scale).roundToInt(), (image.height * l.scale).roundToInt()),
            )
        }
    }
}

/** Topmost loose piece whose actual (tabbed) shape contains the screen point. */
private fun hitTest(cut: CutPuzzle, layout: BoardLayout, pos: Offset): Int? {
    val sx = (pos.x - layout.originX) / layout.scale
    val sy = (pos.y - layout.originY) / layout.scale
    return cut.board.pieceAt { i ->
        val lx = sx - (cut.board.x(i) * cut.cellWidth - cut.padX)
        val ly = sy - (cut.board.y(i) * cut.cellHeight - cut.padY)
        cut.pieces[i].region.contains(lx.toInt(), ly.toInt())
    }
}
