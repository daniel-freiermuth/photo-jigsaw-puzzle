package dev.daniel.jigsaw.puzzle

import kotlin.math.hypot
import kotlin.random.Random

/**
 * Pure game state for a [grid] jigsaw, in grid units relative to the board's top-left corner.
 * Piece `i` sits at row `i / cols`, column `i % cols`; its solved position is (col, row). Positions
 * are the top-left of the piece's unrotated cell; a rotated piece turns around its cell centre.
 *
 * Pieces dragged next to a matching neighbour (in the same orientation) join it into a group that
 * moves and turns as one. Optionally, an upright group dropped near its solved spot snaps onto the
 * board and becomes locked. If [rotatable], pieces start out turned by quarter turns.
 *
 * [cellAspect] is cell width / cell height: rotating a group must turn real picture distances, and
 * one grid unit across is not the same length as one grid unit down.
 */
class PuzzleBoard(
    val grid: Grid,
    private val cellAspect: Float = 1f,
    val rotatable: Boolean = false,
) {
    val rows get() = grid.rows
    val cols get() = grid.cols
    val size = rows * cols
    private val xs = FloatArray(size) { col(it).toFloat() }
    private val ys = FloatArray(size) { row(it).toFloat() }

    /** Clockwise quarter turns, 0..3. */
    private val turns = IntArray(size)
    private val groupOf = IntArray(size) { it }
    private val locked = BooleanArray(size)
    private val order = MutableList(size) { it }

    /** Restores a board saved with [snapshot]; throws [IllegalArgumentException] if it doesn't fit. */
    constructor(grid: Grid, snapshot: BoardSnapshot, cellAspect: Float = 1f) :
        this(grid, cellAspect, snapshot.rotatable) {
        snapshot.requireFits(grid)
        snapshot.xs.forEachIndexed { i, v -> xs[i] = v }
        snapshot.ys.forEachIndexed { i, v -> ys[i] = v }
        snapshot.turns.forEachIndexed { i, v -> turns[i] = v.mod(QUARTERS) }
        snapshot.groups.forEachIndexed { i, v -> groupOf[i] = v }
        snapshot.locked.forEachIndexed { i, v -> locked[i] = v }
        order.clear()
        order.addAll(snapshot.order)
    }

    /** Indices bottom → top. */
    val drawOrder: List<Int> get() = order

    fun x(i: Int) = xs[i]

    fun y(i: Int) = ys[i]

    /** Clockwise quarter turns of piece [i], 0..3. */
    fun turns(i: Int) = turns[i]

    /** Every piece joined into one group, upright, whether or not it sits on the board. */
    val isSolved get() = groupOf.all { it == groupOf[0] } && turns[0] == 0

    /** Copy of the full mutable state (a fresh copy on every read), for persisting a puzzle in progress. */
    val snapshot
        get() = BoardSnapshot(
            xs = xs.toList(),
            ys = ys.toList(),
            groups = groupOf.toList(),
            locked = locked.toList(),
            order = order.toList(),
            turns = turns.toList(),
            rotatable = rotatable,
        )

    /** Places every piece at a random spot so that its cell lies within [bounds], turned at random if [rotatable]. */
    fun scatter(bounds: Bounds, random: Random) {
        for (i in 0 until size) {
            xs[i] = randomIn(bounds.minX, bounds.maxX - 1f, random)
            ys[i] = randomIn(bounds.minY, bounds.maxY - 1f, random)
            if (rotatable) turns[i] = random.nextInt(QUARTERS)
        }
        order.shuffle(random)
    }

    /** Topmost movable piece for which [hit] is true. */
    fun pieceAt(hit: (Int) -> Boolean): Int? = order.asReversed().firstOrNull { !locked[it] && hit(it) }

    /** Raises the piece's whole group above everything else, keeping its internal stacking. */
    fun bringToFront(i: Int) {
        val group = order.filter { sameGroup(it, i) }
        order.removeAll(group)
        order.addAll(group)
    }

    fun moveGroup(i: Int, dx: Float, dy: Float) {
        for (m in members(i)) {
            xs[m] += dx
            ys[m] += dy
        }
    }

    /**
     * Turns piece [i]'s group a quarter turn clockwise around [i]'s centre. Does nothing unless the
     * board is [rotatable] and the group is loose.
     */
    fun rotateGroup(i: Int) {
        if (!rotatable || locked[i]) return
        val pivotX = xs[i]
        val pivotY = ys[i]
        for (m in members(i)) {
            val (dx, dy) = turned(xs[m] - pivotX, ys[m] - pivotY, 1)
            xs[m] = pivotX + dx
            ys[m] = pivotY + dy
            turns[m] = (turns[m] + 1) % QUARTERS
        }
    }

    /**
     * Shifts the group just enough that at least [overlap] cells of its bounding box stay within
     * [bounds] on each axis, so it can always be grabbed again. The rest may hang off-screen:
     * large groups must be able to sit flush against an edge to meet their counterparts.
     */
    fun keepGroupReachable(i: Int, bounds: Bounds, overlap: Float = MIN_REACHABLE_OVERLAP) {
        val group = groupBounds(i)
        val dx = when {
            group.maxX < bounds.minX + overlap -> bounds.minX + overlap - group.maxX
            group.minX > bounds.maxX - overlap -> bounds.maxX - overlap - group.minX
            else -> 0f
        }
        val dy = when {
            group.maxY < bounds.minY + overlap -> bounds.minY + overlap - group.maxY
            group.minY > bounds.maxY - overlap -> bounds.maxY - overlap - group.minY
            else -> 0f
        }
        moveGroup(i, dx, dy)
    }

    /** Moves the group to a random spot with its bounding box inside [bounds] (top-left in it, if too big). */
    fun scatterGroup(i: Int, bounds: Bounds, random: Random) {
        val group = groupBounds(i)
        val width = group.maxX - group.minX
        val height = group.maxY - group.minY
        moveGroup(
            i,
            randomIn(bounds.minX, bounds.maxX - width, random) - group.minX,
            randomIn(bounds.minY, bounds.maxY - height, random) - group.minY,
        )
    }

    /**
     * Resolves a drop of piece [i]'s group: joins matching neighbours and, if [snapToBoard],
     * locks the group onto the board when it is upright and near its solved spot. Returns true if
     * anything snapped.
     */
    fun drop(i: Int, snapToBoard: Boolean, tolerance: Float = SNAP_TOLERANCE): Boolean {
        var snappedAny = false
        var changed = true
        while (changed) {
            changed = joinNeighbour(i, tolerance) || (snapToBoard && lockIfPlaced(i, tolerance))
            snappedAny = snappedAny || changed
        }
        return snappedAny
    }

    private fun joinNeighbour(i: Int, tolerance: Float): Boolean {
        val (p, n) = members(i).asSequence()
            .flatMap { piece -> neighbours(piece).asSequence().map { piece to it } }
            .firstOrNull { (piece, other) ->
                !sameGroup(piece, other) &&
                    turns[piece] == turns[other] &&
                    distance(misalignment(piece, other)) <= tolerance
            }
            ?: return false
        val (dx, dy) = misalignment(p, n)
        // Never move a locked group; align whichever side is still loose.
        if (locked[p]) moveGroup(n, -dx, -dy) else moveGroup(p, dx, dy)
        merge(p, n)
        return true
    }

    /** How far neighbour [n] sits from where it belongs relative to piece [p], given [p]'s orientation. */
    private fun misalignment(p: Int, n: Int): Pair<Float, Float> {
        val (ex, ey) = turned((col(n) - col(p)).toFloat(), (row(n) - row(p)).toFloat(), turns[p])
        return xs[n] - (xs[p] + ex) to ys[n] - (ys[p] + ey)
    }

    /** Grid offset ([dx], [dy]) turned [quarters] clockwise, as the picture would turn (respecting [cellAspect]). */
    private fun turned(dx: Float, dy: Float, quarters: Int): Pair<Float, Float> {
        // Work in picture proportions (x scaled by the cell aspect), where a quarter turn is (x, y) → (-y, x).
        var x = dx * cellAspect
        var y = dy
        repeat(quarters) {
            val nx = -y
            y = x
            x = nx
        }
        return x / cellAspect to y
    }

    private fun distance(offset: Pair<Float, Float>) = hypot(offset.first, offset.second)

    private fun lockIfPlaced(i: Int, tolerance: Float): Boolean {
        val m = members(i)
        val anchor = if (locked[i] || turns[i] != 0) {
            null
        } else {
            m.firstOrNull { hypot(xs[it] - col(it), ys[it] - row(it)) <= tolerance }
        }
        anchor?.let {
            moveGroup(i, col(it) - xs[it], row(it) - ys[it])
            lock(m)
        }
        return anchor != null
    }

    private fun sameGroup(a: Int, b: Int) = groupOf[a] == groupOf[b]

    private fun row(i: Int) = i / cols

    private fun col(i: Int) = i % cols

    private fun members(i: Int): List<Int> = (0 until size).filter { groupOf[it] == groupOf[i] }

    /** Bounding box of piece [i]'s group, in grid units (each piece covers one cell from its x/y). */
    private fun groupBounds(i: Int): Bounds {
        val m = members(i)
        return Bounds(
            minX = m.minOf { xs[it] },
            minY = m.minOf { ys[it] },
            maxX = m.maxOf { xs[it] } + 1f,
            maxY = m.maxOf { ys[it] } + 1f,
        )
    }

    private fun merge(a: Int, b: Int) {
        val target = groupOf[a]
        val source = groupOf[b]
        val wasLocked = locked[a] || locked[b]
        for (k in 0 until size) if (groupOf[k] == source) groupOf[k] = target
        if (wasLocked) lock(members(a))
    }

    private fun lock(m: List<Int>) {
        m.forEach { locked[it] = true }
        // Locked pieces form the board's background; loose pieces should stay above them.
        order.removeAll(m)
        order.addAll(0, m)
    }

    private fun neighbours(i: Int): List<Int> = buildList {
        val r = row(i)
        val c = col(i)
        if (r > 0) add(i - cols)
        if (r < rows - 1) add(i + cols)
        if (c > 0) add(i - 1)
        if (c < cols - 1) add(i + 1)
    }

    private fun randomIn(min: Float, max: Float, random: Random) =
        if (max <= min) min else min + random.nextFloat() * (max - min)

    companion object {
        /** Max misalignment, in cell units, that still snaps. */
        const val SNAP_TOLERANCE = 0.2f

        /** Cells of a group that must stay on-screen after a drop so it can be grabbed again. */
        const val MIN_REACHABLE_OVERLAP = 0.5f

        const val QUARTERS = 4

        const val QUARTER_TURN_DEGREES = 90f

        /** Offset from a piece's cell corner to its centre (the rotation pivot), in cell units. */
        const val PIECE_CENTER = 0.5f
    }
}

/** Plain-data copy of a [PuzzleBoard]'s state, indexed by piece. */
data class BoardSnapshot(
    val xs: List<Float>,
    val ys: List<Float>,
    val groups: List<Int>,
    val locked: List<Boolean>,
    /** Piece indices bottom → top. */
    val order: List<Int>,
    /** Clockwise quarter turns per piece; empty for saves from before rotation existed (all upright). */
    val turns: List<Int> = emptyList(),
    val rotatable: Boolean = false,
)

/**
 * Throws [IllegalArgumentException] unless this snapshot describes a [grid]-sized board: every
 * per-piece list sized rows × cols ([turns][BoardSnapshot.turns] may be empty), the draw order a
 * permutation of the pieces, and group ids naming pieces.
 */
fun BoardSnapshot.requireFits(grid: Grid) {
    val rows = grid.rows
    val cols = grid.cols
    require(rows >= 1 && cols >= 1) { "A board needs at least one row and column, not $rows×$cols" }
    // Long: a product that overflows Int must not happen to match the list sizes.
    val n = rows.toLong() * cols
    require(listOf(xs, ys, groups, locked, order).all { it.size.toLong() == n }) {
        "Snapshot does not match a $rows×$cols board"
    }
    require(turns.isEmpty() || turns.size.toLong() == n) { "Snapshot rotations don't match the board" }
    require(order.sorted() == order.indices.toList()) { "Snapshot draw order is not a permutation" }
    require(groups.all { it in order.indices }) { "Snapshot group ids out of range" }
}

/**
 * After the canvas changed shape (and [screen] with it): every group that left [screen] moves to a
 * random spot in [tray], instead of all of them piling up along the edge nearest the old tray.
 * Groups still reachable stay put.
 */
fun PuzzleBoard.keepAllReachable(screen: Bounds, tray: Bounds, random: Random) {
    val groups = snapshot.groups
    for (i in groups.indices.distinctBy { groups[it] }) {
        val x = x(i)
        val y = y(i)
        keepGroupReachable(i, screen)
        if (x(i) != x || y(i) != y) scatterGroup(i, tray, random)
    }
}

/** Whether any piece's centre lies inside the target area of a [grid]-sized board, i.e. the puzzle has begun. */
fun BoardSnapshot.anyOnBoard(grid: Grid): Boolean = xs.indices.any { i ->
    val cx = xs[i] + PuzzleBoard.PIECE_CENTER
    val cy = ys[i] + PuzzleBoard.PIECE_CENTER
    cx in 0f..grid.cols.toFloat() && cy in 0f..grid.rows.toFloat()
}

/**
 * The same layout switched to [rotatable] mode: each group (joined pieces stay joined) is turned a
 * random number of quarter turns when enabling, or back upright when disabling. Positions stay put
 * apart from the turn around each group's first piece.
 */
fun BoardSnapshot.withRotatable(
    rotatable: Boolean,
    grid: Grid,
    cellAspect: Float,
    random: Random,
): BoardSnapshot {
    if (rotatable == this.rotatable) return this
    val board = PuzzleBoard(grid, copy(rotatable = true), cellAspect)
    for (pivot in groups.indices.distinctBy { groups[it] }) {
        val quarters = if (rotatable) random.nextInt(PuzzleBoard.QUARTERS) else -board.turns(pivot)
        repeat(quarters.mod(PuzzleBoard.QUARTERS)) { board.rotateGroup(pivot) }
    }
    return board.snapshot.copy(rotatable = rotatable)
}
