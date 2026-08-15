package dev.farid.stabber.client.path

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

object StandingPositions {
    const val PLAYER_WIDTH = 0.6
    const val PLAYER_HEIGHT = 1.8
    const val STEP_HEIGHT = 0.6
    const val JUMP_HEIGHT = 1.25
    const val THIN_FLOOR_MAX = 0.5
    const val SWEEP_STEP = 0.25
    private const val EPS = 1.0e-4

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
        return sweep(level, from, to) { _, x, z ->
            val feet = BlockPos.containing(x, minY + EPS, z)
            hasClearanceAt(level, x, minY, z, feet)
        }
    }

    fun sweepClearInterpolated(level: Level, from: Vec3, to: Vec3, fromFloor: Double, toFloor: Double): Boolean {
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

    private fun isInWorld(level: Level, pos: BlockPos): Boolean {
        return pos.y > level.minY && !level.isOutsideBuildHeight(pos)
    }
}

data class SupportProfile(
    val allSupported: Boolean,
    val longestGap: Double,
)
