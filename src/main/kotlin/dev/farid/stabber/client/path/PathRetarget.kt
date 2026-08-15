package dev.farid.stabber.client.path

import net.minecraft.core.BlockPos
import java.util.concurrent.atomic.AtomicBoolean

object PathRetarget {
    /**
     * When the target moves, recompute from the 2nd-last path node, then 3rd-last, etc.
     * Keeps the path prefix and splices on a complete suffix.
     * @return null if cancelled, [PathResult.EMPTY] if no splice was found
     */
    fun recompute(
        world: PathingWorld,
        nodes: List<PathNode>,
        goalHint: BlockPos,
        cancelled: AtomicBoolean,
    ): PathResult? {
        if (nodes.size < 2) return PathResult.EMPTY

        for (i in (nodes.size - 2) downTo 0) {
            if (cancelled.get()) return null
            val suffix = AStarPathfinder.find(world, nodes[i].pos, goalHint, cancelled) ?: return null
            if (!suffix.complete || suffix.nodes.isEmpty()) continue

            val prefix = nodes.subList(0, i)
            val spliced = PathResult(
                nodes = PathSimplifier.simplify(prefix + suffix.nodes),
                complete = true,
                goal = suffix.goal,
            )
            return dropNodesThatRecedeFromGoal(world, spliced, goalHint, cancelled)
        }
        return PathResult.EMPTY
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
        val maxRepairs = path.nodes.size.coerceAtLeast(1)
        while (i < path.nodes.size - 1 && repairs < maxRepairs) {
            if (cancelled.get()) return null
            val nodes = path.nodes
            val end = nodes.last().pos
            val previous = nodes[i]
            val next = nodes[i + 1]
            if (next.pos.distSqr(end) < previous.pos.distSqr(end)) {
                i++
                continue
            }

            val suffix = AStarPathfinder.find(world, previous.pos, goalHint, cancelled) ?: return null
            if (!suffix.complete || suffix.nodes.isEmpty()) {
                i++
                continue
            }

            val suffixEnd = suffix.nodes.last().pos
            val suffixNext = suffix.nodes.getOrNull(1)
            if (suffixNext != null && suffixNext.pos.distSqr(suffixEnd) >= previous.pos.distSqr(suffixEnd)) {
                i++
                continue
            }

            path = PathResult(
                nodes = PathSimplifier.simplify(nodes.subList(0, i) + suffix.nodes),
                complete = true,
                goal = suffix.goal,
            )
            repairs++
        }
        return path
    }
}
