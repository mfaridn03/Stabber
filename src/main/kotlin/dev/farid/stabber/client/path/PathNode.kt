package dev.farid.stabber.client.path

import net.minecraft.core.BlockPos

enum class MoveType {
    WALK,
    JUMP,
    DROP,
}

class PathNode(
    val pos: BlockPos,
    val floorY: Double,
    var parent: PathNode? = null,
    var g: Double = 0.0,
    var h: Double = 0.0,
    var incoming: MoveType = MoveType.WALK,
) {
    val f: Double
        get() = g + h

    val packed: Long
        get() = pos.asLong()
}

/**
 * @param raw grid path as expanded, kept for retargeting and splicing
 * @param nodes string-pulled waypoints for display and following
 */
data class PathResult(
    val raw: List<PathNode>,
    val nodes: List<PathNode>,
    val complete: Boolean,
    val goal: BlockPos?,
) {
    companion object {
        val EMPTY = PathResult(emptyList(), emptyList(), complete = false, goal = null)

        /**
         * String-pulling needs the world, so results are built on the pathfinding thread where the
         * frozen snapshot is still in hand rather than lazily off the render thread.
         */
        fun of(level: PathingWorld, raw: List<PathNode>, complete: Boolean, goal: BlockPos?): PathResult {
            return PathResult(raw, PathStringPuller.pull(level, raw), complete, goal)
        }
    }
}
