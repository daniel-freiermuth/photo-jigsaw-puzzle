package dev.daniel.jigsaw.puzzle

import kotlin.math.hypot
import kotlin.random.Random

/**
 * Pure game state for a rows × cols jigsaw, in grid units relative to the board's top-left corner.
 * Piece `i` sits at row `i / cols`, column `i % cols`; its solved position is (col, row).
 *
 * Pieces dragged next to a matching neighbour join it into a group that moves as one. Optionally,
 * a group dropped near its solved spot snaps onto the board and becomes locked.
 */
class PuzzleBoard(val rows: Int, val cols: Int) {
    val size = rows * cols
    private val xs = FloatArray(size) { col(it).toFloat() }
    private val ys = FloatArray(size) { row(it).toFloat() }
    private val groupOf = IntArray(size) { it }
    private val locked = BooleanArray(size)
    private val order = MutableList(size) { it }

    /** Indices bottom → top. */
    val drawOrder: List<Int> get() = order

    fun x(i: Int) = xs[i]

    fun y(i: Int) = ys[i]

    fun isLocked(i: Int) = locked[i]

    /** Every piece joined into one group, whether or not it sits on the board. */
    val isSolved get() = groupOf.all { it == groupOf[0] }

    fun sameGroup(a: Int, b: Int) = groupOf[a] == groupOf[b]

    /** Places every piece at a random spot so that its cell lies within [bounds]. */
    fun scatter(bounds: Bounds, random: Random) {
        for (i in 0 until size) {
            xs[i] = randomIn(bounds.minX, bounds.maxX - 1f, random)
            ys[i] = randomIn(bounds.minY, bounds.maxY - 1f, random)
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
     * Shifts the group just enough that at least [overlap] cells of its bounding box stay within
     * [bounds] on each axis, so it can always be grabbed again. The rest may hang off-screen:
     * large groups must be able to sit flush against an edge to meet their counterparts.
     */
    fun keepGroupReachable(i: Int, bounds: Bounds, overlap: Float = MIN_REACHABLE_OVERLAP) {
        val m = members(i)
        val left = m.minOf { xs[it] }
        val top = m.minOf { ys[it] }
        val right = m.maxOf { xs[it] } + 1f
        val bottom = m.maxOf { ys[it] } + 1f
        val dx = when {
            right < bounds.minX + overlap -> bounds.minX + overlap - right
            left > bounds.maxX - overlap -> bounds.maxX - overlap - left
            else -> 0f
        }
        val dy = when {
            bottom < bounds.minY + overlap -> bounds.minY + overlap - bottom
            top > bounds.maxY - overlap -> bounds.maxY - overlap - top
            else -> 0f
        }
        moveGroup(i, dx, dy)
    }

    /**
     * Resolves a drop of piece [i]'s group: joins matching neighbours and, if [snapToBoard],
     * locks the group onto the board when it is near its solved spot. Returns true if anything snapped.
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
                !sameGroup(piece, other) && distance(misalignment(piece, other)) <= tolerance
            }
            ?: return false
        val (dx, dy) = misalignment(p, n)
        // Never move a locked group; align whichever side is still loose.
        if (locked[p]) moveGroup(n, -dx, -dy) else moveGroup(p, dx, dy)
        merge(p, n)
        return true
    }

    /** How far neighbour [n] sits from where it belongs relative to piece [p]. */
    private fun misalignment(p: Int, n: Int): Pair<Float, Float> =
        xs[n] - (xs[p] + col(n) - col(p)) to ys[n] - (ys[p] + row(n) - row(p))

    private fun distance(offset: Pair<Float, Float>) = hypot(offset.first, offset.second)

    private fun lockIfPlaced(i: Int, tolerance: Float): Boolean {
        val m = members(i)
        val anchor = if (locked[i]) null else m.firstOrNull { hypot(xs[it] - col(it), ys[it] - row(it)) <= tolerance }
        anchor?.let {
            moveGroup(i, col(it) - xs[it], row(it) - ys[it])
            lock(m)
        }
        return anchor != null
    }

    private fun row(i: Int) = i / cols

    private fun col(i: Int) = i % cols

    private fun members(i: Int): List<Int> = (0 until size).filter { groupOf[it] == groupOf[i] }

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
    }
}
