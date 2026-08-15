package dev.farid.stabber.client.path

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.material.FluidState
import kotlin.math.max
import kotlin.math.min

/**
 * Immutable chunk-section copy taken on the game thread for off-thread collision queries.
 */
class FrozenPathingWorld private constructor(
    private val worldMinY: Int,
    private val worldHeight: Int,
    private val minSectionY: Int,
    private val maxSectionY: Int,
    private val loadedChunks: LongOpenHashSet,
    private val sections: Long2ObjectOpenHashMap<LevelChunkSection>,
) : PathingWorld {
    override fun isLoaded(pos: BlockPos): Boolean {
        if (isOutsideBuildHeight(pos)) return false
        val sectionY = SectionPos.blockToSectionCoord(pos.y)
        if (sectionY < minSectionY || sectionY > maxSectionY) return false
        return loadedChunks.contains(ChunkPos.pack(
            SectionPos.blockToSectionCoord(pos.x),
            SectionPos.blockToSectionCoord(pos.z),
        ))
    }

    override fun getBlockEntity(pos: BlockPos): BlockEntity? = null

    override fun getBlockState(pos: BlockPos): BlockState {
        if (!isLoaded(pos)) return AIR
        val section = sections.get(SectionPos.asLong(
            SectionPos.blockToSectionCoord(pos.x),
            SectionPos.blockToSectionCoord(pos.y),
            SectionPos.blockToSectionCoord(pos.z),
        )) ?: return AIR
        return section.getBlockState(pos.x and 15, pos.y and 15, pos.z and 15)
    }

    override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState

    override fun getHeight(): Int = worldHeight

    override fun getMinY(): Int = worldMinY

    companion object {
        private val AIR: BlockState = Blocks.AIR.defaultBlockState()
        private const val Y_PAD = 4

        fun capture(level: Level, anchors: Iterable<BlockPos>): FrozenPathingWorld {
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var minZ = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var maxZ = Int.MIN_VALUE
            var any = false
            for (pos in anchors) {
                any = true
                minX = min(minX, pos.x)
                minY = min(minY, pos.y)
                minZ = min(minZ, pos.z)
                maxX = max(maxX, pos.x)
                maxY = max(maxY, pos.y)
                maxZ = max(maxZ, pos.z)
            }
            if (!any) {
                return FrozenPathingWorld(
                    level.minY,
                    level.height,
                    level.minSectionY,
                    level.minSectionY,
                    LongOpenHashSet(),
                    Long2ObjectOpenHashMap(),
                )
            }

            val radius = AStarPathfinder.MAX_RADIUS
            minX -= radius
            maxX += radius
            minZ -= radius
            maxZ += radius
            minY = max(level.minY, minY - AStarPathfinder.MAX_DROP_SCAN - Y_PAD)
            maxY = min(level.maxY, maxY + Y_PAD)

            val minChunkX = SectionPos.blockToSectionCoord(minX)
            val maxChunkX = SectionPos.blockToSectionCoord(maxX)
            val minChunkZ = SectionPos.blockToSectionCoord(minZ)
            val maxChunkZ = SectionPos.blockToSectionCoord(maxZ)
            val minSectionY = max(level.minSectionY, SectionPos.blockToSectionCoord(minY))
            val maxSectionY = min(level.maxSectionY, SectionPos.blockToSectionCoord(maxY))

            val loadedChunks = LongOpenHashSet()
            val sections = Long2ObjectOpenHashMap<LevelChunkSection>()
            val source = level.chunkSource
            for (chunkX in minChunkX..maxChunkX) {
                for (chunkZ in minChunkZ..maxChunkZ) {
                    val chunk = source.getChunkNow(chunkX, chunkZ) ?: continue
                    loadedChunks.add(ChunkPos.pack(chunkX, chunkZ))
                    for (sectionY in minSectionY..maxSectionY) {
                        val section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY))
                        if (section.hasOnlyAir()) continue
                        sections.put(SectionPos.asLong(chunkX, sectionY, chunkZ), section.copy())
                    }
                }
            }

            return FrozenPathingWorld(
                level.minY,
                level.height,
                minSectionY,
                maxSectionY,
                loadedChunks,
                sections,
            )
        }
    }
}
