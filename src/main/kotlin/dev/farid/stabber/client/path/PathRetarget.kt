package dev.farid.stabber.client.path

import net.minecraft.core.BlockPos
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

object PathRetarget {
    private const val ON_PATH_CHEBYSHEV = 2

    /**
     * When the target moves, drop nodes the player has already left, then splice a new suffix
     * from the remaining path. If the player is no longer near the old path, search from
     * their current standing position instead.
     * @return null if cancelled, [PathResult.EMPTY] if no path was found
     */
    fun recompute(
        world: PathingWorld,
        nodes: List<PathNode>,
        startHint: BlockPos,
        goalHint: BlockPos,
        cancelled: AtomicBoolean,
    ): PathResult? {
        if (cancelled.get()) return null
        val start = AStarPathfinder.resolveStanding(world, startHint)
            ?: return PathResult.EMPTY

        val remaining = remainingFromPlayer(nodes, start)
        if (remaining.size < 2) {
            return AStarPathfinder.find(world, start.pos, goalHint, cancelled)
        }

        for (i in (remaining.size - 2) downTo 0) {
            if (cancelled.get()) return null
            val suffix = AStarPathfinder.find(world, remaining[i].pos, goalHint, cancelled) ?: return null
            if (!suffix.complete || suffix.raw.isEmpty()) continue

            val prefix = remaining.subList(0, i)
            val spliced = PathResult.of(
                world,
                raw = PathSimplifier.simplify(prefix + suffix.raw),
                complete = true,
                goal = suffix.goal,
            )
            return dropNodesThatRecedeFromGoal(world, spliced, goalHint, cancelled)
        }
        return AStarPathfinder.find(world, start.pos, goalHint, cancelled)
    }

    private fun remainingFromPlayer(nodes: List<PathNode>, start: PathNode): List<PathNode> {
        if (nodes.isEmpty()) return listOf(start)

        var best = 0
        var bestDist = Int.MAX_VALUE
        for (i in nodes.indices) {
            val dist = chebyshev(nodes[i].pos, start.pos)
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        if (bestDist > ON_PATH_CHEBYSHEV) return emptyList()

        val tail = nodes.subList(best, nodes.size)
        return if (tail.first().pos == start.pos) tail else listOf(start) + tail
    }

    private fun chebyshev(a: BlockPos, b: BlockPos): Int {
        return max(max(abs(a.x - b.x), abs(a.y - b.y)), abs(a.z - b.z))
    }

    /**
     * Each step of the spliced path must get closer to the end node.
     * A U-turning target leaves a walk-history shaped prefix that recedes from the new goal;
     * when that happens, recompute from the previous node so the suffix can cut the loop.
     */
    private fun dropNodesThatRecedeFromGoal(
        world: PathingWorld,
        initial: PathResult,
        goalHint: BlockPos,
        cancelled: AtomicBoolean,
    ): PathResult? {
        var path = initial
        var i = 0
        var repairs = 0
        val maxRepairs = path.raw.size.coerceAtLeast(1)
        while (i < path.raw.size - 1 && repairs < maxRepairs) {
            if (cancelled.get()) return null
            val nodes = path.raw
            val end = nodes.last().pos
            val previous = nodes[i]
            val next = nodes[i + 1]
            if (next.pos.distSqr(end) < previous.pos.distSqr(end)) {
                i++
                continue
            }

            val suffix = AStarPathfinder.find(world, previous.pos, goalHint, cancelled) ?: return null
            if (!suffix.complete || suffix.raw.isEmpty()) {
                i++
                continue
            }

            val suffixEnd = suffix.raw.last().pos
            val suffixNext = suffix.raw.getOrNull(1)
            if (suffixNext != null && suffixNext.pos.distSqr(suffixEnd) >= previous.pos.distSqr(suffixEnd)) {
                i++
                continue
            }

            path = PathResult.of(
                world,
                raw = PathSimplifier.simplify(nodes.subList(0, i) + suffix.raw),
                complete = true,
                goal = suffix.goal,
            )
            repairs++
        }
        return path
    }
}
