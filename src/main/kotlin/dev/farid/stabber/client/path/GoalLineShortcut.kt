package dev.farid.stabber.client.path

import net.minecraft.core.BlockPos

/**
 * Cuts a hybrid path to the goal as soon as a single walk edge is clear.
 *
 * Hybrid assembly routes through the nearest manual node to the target, which can detour when an
 * earlier node (or the player) already has line-of-sight. This pass finds the earliest such exit
 * and replaces the suffix with one WALK to the resolved goal.
 *
 * JUMP/DROP on the graph do not block the cut: [StandingPositions.sweepClearWalking] fails when a
 * real jump or drop is required, so those actions are only skipped when a walk would work anyway.
 */
object GoalLineShortcut {
    data class Result(
        val nodes: List<PathNode>,
        /** Index in the input path that walks directly to the goal, or null if no cut was made. */
        val exitIndex: Int?,
    )

    fun cut(world: PathingWorld, nodes: List<PathNode>, goalHint: BlockPos): Result {
        if (nodes.isEmpty()) return Result(nodes, exitIndex = null)
        val goal = AStarPathfinder.resolveStanding(world, goalHint)
            ?: return Result(nodes, exitIndex = null)

        val last = nodes.size - 1
        for (i in 0 until last) {
            if (canWalk(world, nodes[i], goal)) {
                if (i == last - 1 && nodes[last].pos == goal.pos) {
                    return Result(nodes, exitIndex = null)
                }
                val out = ArrayList<PathNode>(i + 2)
                for (k in 0..i) {
                    out.add(nodes[k])
                }
                out.add(PathNode(goal.pos.immutable(), goal.floorY, incoming = MoveType.WALK))
                return Result(out, exitIndex = i)
            }
        }
        return Result(nodes, exitIndex = null)
    }

    fun canWalk(world: PathingWorld, from: PathNode, to: PathNode): Boolean {
        if (from.pos == to.pos) return true
        val fromCentre = StandingPositions.nodeCentre(from.pos, from.floorY)
        val toCentre = StandingPositions.nodeCentre(to.pos, to.floorY)
        return StandingPositions.sweepClearWalking(world, fromCentre, toCentre, from.floorY, to.floorY)
    }
}
