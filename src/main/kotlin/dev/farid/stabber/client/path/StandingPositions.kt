package dev.farid.stabber.client.path

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object StandingPositions {
    const val PLAYER_WIDTH = 0.6
    const val PLAYER_HEIGHT = 1.8
    const val STEP_HEIGHT = 0.6
    const val JUMP_HEIGHT = 1.25
    const val THIN_FLOOR_MAX = 0.5
    const val SWEEP_STEP = 0.25
    private const val EPS = 1.0e-4
    private const val CORNER_T_EPS = 1.0e-3

    fun playerAabb(x: Double, floorY: Double, z: Double): AABB {
        val center = Vec3(x, floorY + PLAYER_HEIGHT * 0.5, z)
        return AABB.ofSize(center, PLAYER_WIDTH, PLAYER_HEIGHT, PLAYER_WIDTH)
    }

    fun nodeCentre(pos: BlockPos, floorY: Double): Vec3 {
        return Vec3(pos.x + 0.5, floorY, pos.z + 0.5)
    }

    fun floorHeight(level: Level, feetPos: BlockPos): Double? {
        if (!isInWorld(level, feetPos) || !level.isLoaded(feetPos)) return null

        val feetMax = surfaceLocalY(level, feetPos)
        if (feetMax != null) {
            if (feetMax <= THIN_FLOOR_MAX) return feetPos.y + feetMax
            return null
        }

        val below = feetPos.below()
        if (!level.isLoaded(below)) return null
        val belowMax = surfaceLocalY(level, below) ?: return null
        if (belowMax > 1.0 + EPS) return null
        val floorY = below.y + belowMax
        if (floorY < feetPos.y - EPS || floorY > feetPos.y + THIN_FLOOR_MAX) return null
        return floorY
    }

    fun isStandable(level: Level, feetPos: BlockPos): Boolean {
        val floorY = floorHeight(level, feetPos) ?: return false
        return hasClearance(level, feetPos, floorY)
    }

    fun hasClearance(level: Level, feetPos: BlockPos, floorY: Double): Boolean {
        return hasClearanceAt(level, feetPos.x + 0.5, floorY, feetPos.z + 0.5, feetPos)
    }

    fun sweepClear(level: Level, from: Vec3, to: Vec3, minY: Double): Boolean {
        val bodyMinY = minY
        val bodyMaxY = minY + PLAYER_HEIGHT
        if (cutsBlockedPostDiagonal(level, from.x, from.z, to.x, to.z, bodyMinY, bodyMaxY)) {
            return false
        }
        return sweep(level, from, to) { _, x, z ->
            val feet = BlockPos.containing(x, minY + EPS, z)
            hasClearanceAt(level, x, minY, z, feet)
        }
    }

    fun sweepClearInterpolated(level: Level, from: Vec3, to: Vec3, fromFloor: Double, toFloor: Double): Boolean {
        val bodyMinY = min(fromFloor, toFloor)
        val bodyMaxY = max(fromFloor, toFloor) + PLAYER_HEIGHT
        if (cutsBlockedPostDiagonal(level, from.x, from.z, to.x, to.z, bodyMinY, bodyMaxY)) {
            return false
        }
        return sweep(level, from, to) { t, x, z ->
            val floorY = fromFloor + (toFloor - fromFloor) * t
            val feet = BlockPos.containing(x, floorY + EPS, z)
            hasClearanceAt(level, x, floorY, z, feet)
        }
    }

    fun supportProfile(level: Level, from: Vec3, to: Vec3, fromFloor: Double, toFloor: Double): SupportProfile {
        val horizontal = hypot(to.x - from.x, to.z - from.z)
        val steps = max(1, ceil(horizontal / SWEEP_STEP).toInt())
        val sampleSpan = horizontal / steps
        var longestGap = 0.0
        var currentGap = 0.0
        var allSupported = true
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val x = from.x + (to.x - from.x) * t
            val z = from.z + (to.z - from.z) * t
            val expected = fromFloor + (toFloor - fromFloor) * t
            val actual = floorNear(level, x, expected, z)
            val supported = actual != null && abs(actual - expected) <= STEP_HEIGHT
            if (supported) {
                longestGap = max(longestGap, currentGap)
                currentGap = 0.0
            } else {
                allSupported = false
                currentGap += if (i == 0) 0.0 else sampleSpan
            }
        }
        longestGap = max(longestGap, currentGap)
        return SupportProfile(allSupported, longestGap)
    }

    private fun sweep(
        level: Level,
        from: Vec3,
        to: Vec3,
        test: (t: Double, x: Double, z: Double) -> Boolean,
    ): Boolean {
        val horizontal = hypot(to.x - from.x, to.z - from.z)
        val steps = max(1, ceil(horizontal / SWEEP_STEP).toInt())
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val x = from.x + (to.x - from.x) * t
            val z = from.z + (to.z - from.z) * t
            if (!test(t, x, z)) return false
        }
        return true
    }

    private fun hasClearanceAt(level: Level, x: Double, floorY: Double, z: Double, feetHint: BlockPos): Boolean {
        if (!isInWorld(level, feetHint) || !level.isLoaded(feetHint)) return false
        val aabb = playerAabb(x, floorY, z)
        val ignoreBelow = feetHint.below()
        val thinFloor = run {
            val local = surfaceLocalY(level, feetHint)
            local != null && local <= THIN_FLOOR_MAX
        }
        val minX = Mth.floor(aabb.minX)
        val maxX = Mth.floor(aabb.maxX)
        val minY = Mth.floor(aabb.minY)
        val maxY = Mth.floor(aabb.maxY)
        val minZ = Mth.floor(aabb.minZ)
        val maxZ = Mth.floor(aabb.maxZ)
        val query = Shapes.create(aabb)
        val pos = BlockPos.MutableBlockPos()
        for (bx in minX..maxX) {
            for (by in minY..maxY) {
                for (bz in minZ..maxZ) {
                    pos.set(bx, by, bz)
                    if (!level.isLoaded(pos)) return false
                    if (pos == ignoreBelow) continue
                    if (thinFloor && pos == feetHint) continue
                    val shape = collision(level, pos)
                    if (shape.isEmpty) continue
                    val world = shape.move(bx.toDouble(), by.toDouble(), bz.toDouble())
                    if (Shapes.joinIsNotEmpty(world, query, BooleanOp.AND)) return false
                }
            }
        }
        return true
    }

    private fun floorNear(level: Level, x: Double, expectedFloor: Double, z: Double): Double? {
        val primary = BlockPos.containing(x, expectedFloor + EPS, z)
        floorHeight(level, primary)?.let { return it }
        val below = primary.below()
        floorHeight(level, below)?.let { return it }
        val above = primary.above()
        return floorHeight(level, above)
    }

    private fun surfaceLocalY(level: Level, pos: BlockPos): Double? {
        val shape = collision(level, pos)
        if (shape.isEmpty) return null
        val local = shape.max(Direction.Axis.Y, 0.5, 0.5)
        if (!local.isFinite() || local <= 0.0) return null
        return local
    }

    private fun collision(level: Level, pos: BlockPos): VoxelShape {
        return level.getBlockState(pos).getCollisionShape(level, pos)
    }

    /**
     * Mirrors vanilla WalkNodeEvaluator.isDiagonalValid for player-width entities:
     * cutting a grid corner flanked by two fence/wall posts is impassable.
     */
    private fun cutsBlockedPostDiagonal(
        level: Level,
        x0: Double,
        z0: Double,
        x1: Double,
        z1: Double,
        bodyMinY: Double,
        bodyMaxY: Double,
    ): Boolean {
        val dx = x1 - x0
        val dz = z1 - z0
        if (abs(dx) <= EPS || abs(dz) <= EPS) return false

        val minCx = ceil(min(x0, x1) - CORNER_T_EPS).toInt()
        val maxCx = floor(max(x0, x1) + CORNER_T_EPS).toInt()
        val minCz = ceil(min(z0, z1) - CORNER_T_EPS).toInt()
        val maxCz = floor(max(z0, z1) + CORNER_T_EPS).toInt()
        if (minCx > maxCx || minCz > maxCz) return false

        val posA = BlockPos.MutableBlockPos()
        val posB = BlockPos.MutableBlockPos()
        val minBlockY = Mth.floor(bodyMinY)
        val maxBlockY = Mth.floor(bodyMaxY - EPS)

        for (cx in minCx..maxCx) {
            val tX = (cx - x0) / dx
            if (tX < -CORNER_T_EPS || tX > 1.0 + CORNER_T_EPS) continue
            for (cz in minCz..maxCz) {
                val tZ = (cz - z0) / dz
                if (tZ < -CORNER_T_EPS || tZ > 1.0 + CORNER_T_EPS) continue
                if (abs(tX - tZ) > CORNER_T_EPS) continue

                // Flanking cells for the corner cut (vanilla east/north neighbors relative to step).
                if (dx > 0.0 && dz > 0.0) {
                    posA.set(cx, 0, cz - 1)
                    posB.set(cx - 1, 0, cz)
                } else if (dx > 0.0 && dz < 0.0) {
                    posA.set(cx, 0, cz)
                    posB.set(cx - 1, 0, cz - 1)
                } else if (dx < 0.0 && dz > 0.0) {
                    posA.set(cx - 1, 0, cz - 1)
                    posB.set(cx, 0, cz)
                } else {
                    posA.set(cx - 1, 0, cz)
                    posB.set(cx, 0, cz - 1)
                }

                for (y in minBlockY..maxBlockY) {
                    posA.y = y
                    posB.y = y
                    if (isBlockingPost(level, posA, bodyMinY, bodyMaxY) &&
                        isBlockingPost(level, posB, bodyMinY, bodyMaxY)
                    ) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun isPostLike(state: BlockState): Boolean {
        if (state.`is`(BlockTags.FENCES) || state.`is`(BlockTags.WALLS)) return true
        val block = state.block
        return block is FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)
    }

    private fun isBlockingPost(level: Level, pos: BlockPos, bodyMinY: Double, bodyMaxY: Double): Boolean {
        if (!level.isLoaded(pos)) return false
        val state = level.getBlockState(pos)
        if (!isPostLike(state)) return false
        val shape = state.getCollisionShape(level, pos)
        if (shape.isEmpty) return false
        val shapeMin = pos.y + shape.min(Direction.Axis.Y)
        val shapeMax = pos.y + shape.max(Direction.Axis.Y)
        return bodyMinY < shapeMax - EPS && bodyMaxY > shapeMin + EPS
    }

    private fun isInWorld(level: Level, pos: BlockPos): Boolean {
        return pos.y > level.minY && !level.isOutsideBuildHeight(pos)
    }
}

data class SupportProfile(
    val allSupported: Boolean,
    val longestGap: Double,
)
