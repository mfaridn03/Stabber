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
    val nodes: List<PathNode>,
    val complete: Boolean,
    val goal: BlockPos?,
) {
    companion object {
        val EMPTY = PathResult(emptyList(), complete = false, goal = null)
    }
}
