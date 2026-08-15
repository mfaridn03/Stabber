package dev.farid.stabber.client.movement

import dev.farid.stabber.client.path.MoveType
import dev.farid.stabber.client.path.PathNode
import dev.farid.stabber.client.path.PathfindingController
import dev.farid.stabber.client.path.StandingPositions
import dev.farid.stabber.client.rotation.RotationController
import dev.farid.stabber.client.target.TargetManager
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Drives [RotationController] and [MovementController] from the live path.
 * Following starts only via [requestStart] (`/start`), never from pathfinding alone.
 */
object PathFollower {
    private const val NODE_REACH_XZ = 0.5
    private const val NODE_REACH_Y = 0.6
    private const val SPRINT_YAW_TOLERANCE = 20.0f
    private const val SPRINT_MIN_REMAINING = 2
    private const val STUCK_SPEED_EPS = 0.01
    private const val STUCK_TICKS = 8

    /** Actively driving movement/rotation along the path. */
    var following: Boolean = false
        private set

    /**
     * `/start` while the initial path is still calculating. Becomes [following] once
     * [PathfindingController] publishes a non-empty path.
     */
    var pendingStart: Boolean = false
        private set

    private var stuckTicks = 0
    private var lastX = 0.0
    private var lastZ = 0.0

    /**
     * Arms or starts path following.
     *
     * - No target, or pathfinding never started → no-op.
     * - Path already ready → start following immediately.
     * - Pathfinding in flight with no path yet → arm [pendingStart]; begin when path arrives.
     */
    fun requestStart(): Boolean {
        if (TargetManager.target == null) return false
        if (!PathfindingController.active) return false

        if (PathfindingController.path.nodes.isNotEmpty()) {
            following = true
            pendingStart = false
            return true
        }

        // active + empty path ⇒ initial search still running (or about to publish)
        pendingStart = true
        return true
    }

    fun tick(minecraft: Minecraft) {
        if (pendingStart && !PathfindingController.active) {
            pendingStart = false
        }
        if (pendingStart && PathfindingController.path.nodes.isNotEmpty()) {
            following = true
            pendingStart = false
        }

        if (!following) {
            releaseControls()
            return
        }

        val player = minecraft.player
        val level = minecraft.level
        if (player == null || level == null || !PathfindingController.active) {
            stop()
            return
        }

        val nodes = PathfindingController.path.nodes
        if (nodes.isEmpty()) {
            releaseControls()
            return
        }

        val target = TargetManager.target
        val inAttackRange = target != null && canAttack(player, target)

        if (inAttackRange) {
            RotationController.lookAt(player, target)
            MovementController.apply(forward = false, sprint = false)
            stuckTicks = 0
            rememberPos(player)
            return
        }

        val index = nextNodeIndex(player, nodes) ?: run {
            releaseControls()
            return
        }
        val node = nodes[index]
        val aim = StandingPositions.nodeCentre(node.pos, node.floorY)

        val yaw = yawToward(player, aim)
        RotationController.rotateTo(yaw, null)

        val yawError = abs(Mth.degreesDifference(player.yRot, yaw))
        val remaining = nodes.size - index
        val sprint = yawError <= SPRINT_YAW_TOLERANCE && remaining > SPRINT_MIN_REMAINING

        val needJump = shouldJump(player, node)
        MovementController.apply(forward = true, sprint = sprint, jump = needJump)

        updateStuck(player)
        if (stuckTicks >= STUCK_TICKS && player.onGround()) {
            MovementController.requestJump()
            stuckTicks = 0
        }
    }

    fun stop() {
        following = false
        pendingStart = false
        releaseControls()
        stuckTicks = 0
    }

    private fun releaseControls() {
        MovementController.release()
        RotationController.cancel()
    }

    private fun canAttack(player: LocalPlayer, target: LivingEntity): Boolean {
        if (!player.hasLineOfSight(target)) return false
        return player.isWithinEntityInteractionRange(target, 0.0)
    }

    /**
     * Closest node by XZ + floor proximity, then advance while the player is within reach of it.
     * Recomputed each tick so path recomputes stay correct.
     */
    private fun nextNodeIndex(player: LocalPlayer, nodes: List<PathNode>): Int? {
        if (nodes.isEmpty()) return null

        var best = 0
        var bestScore = Double.POSITIVE_INFINITY
        val px = player.x
        val pz = player.z
        val py = player.y
        for (i in nodes.indices) {
            val n = nodes[i]
            val cx = n.pos.x + 0.5
            val cz = n.pos.z + 0.5
            val xz = hypot(px - cx, pz - cz)
            val y = abs(py - n.floorY)
            val score = xz + y * 0.5
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }

        var index = best
        while (index < nodes.size && reached(player, nodes[index])) {
            index++
        }
        return if (index < nodes.size) index else null
    }

    private fun reached(player: LocalPlayer, node: PathNode): Boolean {
        val cx = node.pos.x + 0.5
        val cz = node.pos.z + 0.5
        val xz = hypot(player.x - cx, player.z - cz)
        val y = abs(player.y - node.floorY)
        return xz <= NODE_REACH_XZ && y <= NODE_REACH_Y
    }

    private fun yawToward(player: LocalPlayer, point: Vec3): Float {
        val dx = point.x - player.x
        val dz = point.z - player.z
        return Mth.wrapDegrees(Math.toDegrees(Mth.atan2(dz, dx)).toFloat() - 90.0f)
    }

    private fun shouldJump(player: LocalPlayer, node: PathNode): Boolean {
        return node.incoming == MoveType.JUMP && player.onGround()
    }

    private fun updateStuck(player: LocalPlayer) {
        val moved = hypot(player.x - lastX, player.z - lastZ)
        if (moved < STUCK_SPEED_EPS && player.onGround()) {
            stuckTicks++
        } else {
            stuckTicks = 0
        }
        rememberPos(player)
    }

    private fun rememberPos(player: LocalPlayer) {
        lastX = player.x
        lastZ = player.z
    }
}
