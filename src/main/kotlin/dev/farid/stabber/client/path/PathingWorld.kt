package dev.farid.stabber.client.path

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState

interface PathingWorld : BlockGetter {
    fun isLoaded(pos: BlockPos): Boolean
}

class LevelPathingWorld(private val level: Level) : PathingWorld {
    override fun isLoaded(pos: BlockPos): Boolean = level.isLoaded(pos)

    override fun getBlockEntity(pos: BlockPos): BlockEntity? = level.getBlockEntity(pos)

    override fun getBlockState(pos: BlockPos): BlockState = level.getBlockState(pos)

    override fun getFluidState(pos: BlockPos): FluidState = level.getFluidState(pos)

    override fun getHeight(): Int = level.height

    override fun getMinY(): Int = level.minY
}
