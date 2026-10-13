package dev.daniel.jigsaw.puzzle

import kotlin.math.hypot
import kotlin.random.Random

/**
 * Pure game state for a rows × cols jigsaw, in grid units relative to the board's top-left corner.
 * Piece `i` sits at row `i / cols`, column `i % cols`; its solved position is (col, row).
 *
 * Pieces dragged next to a matching neighbour join it into a group that moves as one; a group
 * dropped near its solved spot snaps onto the board and becomes locked.
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

    fun row(i: Int) = i / cols
    fun col(i: Int) = i % cols
    fun x(i: Int) = xs[i]
    fun y(i: Int) = ys[i]
    fun isLocked(i: Int) = locked[i]
    val isSolved get() = locked.all { it }
    fun sameGroup(a: Int, b: Int) = groupOf[a] == groupOf[b]
    fun members(i: Int): List<Int> = (0 until size).filter { groupOf[it] == groupOf[i] }

    /** Places every piece at a random spot so that its cell lies within the given bounds. */
    fun scatter(minX: Float, minY: Float, maxX: Float, maxY: Float, random: Random) {
        for (i in 0 until size) {
            xs[i] = randomIn(minX, maxX - 1f, random)
            ys[i] = randomIn(minY, maxY - 1f, random)
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

    /** Shifts the group so its cells' bounding box stays inside the bounds (top-left wins if too big). */
    fun keepGroupInside(i: Int, minX: Float, minY: Float, maxX: Float, maxY: Float) {
        val m = members(i)
        val left = m.minOf { xs[it] }
        val top = m.minOf { ys[it] }
        val right = m.maxOf { xs[it] } + 1f
        val bottom = m.maxOf { ys[it] } + 1f
        val dx = when {
            left < minX -> minX - left
            right > maxX -> maxOf(maxX - right, minX - left)
            else -> 0f
        }
        val dy = when {
            top < minY -> minY - top
            bottom > maxY -> maxOf(maxY - bottom, minY - top)
            else -> 0f
        }
        moveGroup(i, dx, dy)
    }

    /**
     * Resolves a drop of piece [i]'s group: joins matching neighbours and snaps onto the board.
     * Returns true if anything snapped.
     */
    fun drop(i: Int, tolerance: Float = SNAP_TOLERANCE): Boolean {
        var snappedAny = false
        var changed = true
        while (changed) {
            changed = joinNeighbour(i, tolerance) || lockIfPlaced(i, tolerance)
            snappedAny = snappedAny || changed
        }
        return snappedAny
    }

    private fun joinNeighbour(i: Int, tolerance: Float): Boolean {
        for (p in members(i)) {
            for (n in neighbours(p)) {
                if (sameGroup(p, n)) continue
                val dx = xs[n] - (xs[p] + col(n) - col(p))
                val dy = ys[n] - (ys[p] + row(n) - row(p))
                if (hypot(dx, dy) > tolerance) continue
                // Never move a locked group; align whichever side is still loose.
                if (locked[p]) moveGroup(n, -dx, -dy) else moveGroup(p, dx, dy)
                merge(p, n)
                return true
            }
        }
        return false
    }

    private fun lockIfPlaced(i: Int, tolerance: Float): Boolean {
        if (locked[i]) return false
        val m = members(i)
        val anchor = m.firstOrNull { hypot(xs[it] - col(it), ys[it] - row(it)) <= tolerance } ?: return false
        moveGroup(i, col(anchor) - xs[anchor], row(anchor) - ys[anchor])
        lock(m)
        return true
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
    }
}
