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

data class PathResult(
    val raw: List<PathNode>,
    val complete: Boolean,
    val goal: BlockPos?,
    val optimized: List<PathNode>? = null,
) {
    /** Best path for display/follow: string-pulled when ready, otherwise raw. */
    val nodes: List<PathNode>
        get() = optimized ?: raw

    companion object {
        val EMPTY = PathResult(emptyList(), complete = false, goal = null)
    }
}
