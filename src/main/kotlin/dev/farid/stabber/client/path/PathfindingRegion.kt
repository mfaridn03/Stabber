package dev.farid.stabber.client.path

import net.minecraft.core.BlockPos

object PathfindingRegion {
    const val MIN_X = 211
    const val MAX_X = 299
    const val MIN_Z = 81
    const val MAX_Z = 146
    const val MIN_Y = 82
    const val MAX_Y = 111

    fun contains(pos: BlockPos): Boolean {
        return pos.x in MIN_X..MAX_X && pos.z in MIN_Z..MAX_Z && pos.y in MIN_Y..MAX_Y
    }
}
