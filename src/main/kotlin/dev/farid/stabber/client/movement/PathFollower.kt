package dev.farid.stabber.client.movement

import dev.farid.stabber.client.path.MoveType
import dev.farid.stabber.client.path.PathNode
import dev.farid.stabber.client.path.PathfindingController
import dev.farid.stabber.client.path.StandingPositions
import dev.farid.stabber.client.rotation.RotationController
import dev.farid.stabber.client.target.TargetManager
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
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
    /** Covers a full 1x1 cell (corner is ~0.71 from centre). */
    private const val NODE_REACH_XZ = 0.75
    /** Don't aim at a waypoint this close; look further along the path. */
    private const val AIM_MIN_XZ = 1.5
    /** Degrees per frame while path-following: a brisk head turn, not an instant snap. */
    private const val TURN_MAX_STEP = 9.0
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

    /**
     * Block position of the waypoint currently being walked to, or null when between legs.
     *
     * Exposed so a recompute can start from the leg in progress instead of rewriting it.
     */
    var lockedNode: BlockPos? = null
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
            lockedNode = null
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
            lockedNode = null
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

        val followIndex = followIndex(player, nodes) ?: run {
            releaseControls()
            return
        }
        val node = nodes[followIndex]
        val aim = aimPoint(player, nodes, followIndex)

        val yaw = yawToward(player, aim)
        RotationController.lookAt(player, aim, TURN_MAX_STEP)

        val yawError = abs(Mth.degreesDifference(player.yRot, yaw))
        val remaining = nodes.size - followIndex
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
        lockedNode = null
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
     * Waypoint being walked to. The leg in progress is pinned to [lockedNode], so a path published
     * mid-stride can only change waypoints past it: re-running the nearest-node scan on a fresh path
     * can land on a different waypoint, and the view whips around to follow it.
     *
     * The lock is released once that waypoint is reached, or if a new path no longer contains it.
     */
    private fun followIndex(player: LocalPlayer, nodes: List<PathNode>): Int? {
        if (nodes.isEmpty()) {
            lockedNode = null
            return null
        }

        val locked = lockedNode?.let { pos -> nodes.indexOfFirst { it.pos == pos } }?.takeIf { it >= 0 }
        var index = locked ?: nearestNodeIndex(player, nodes)
        while (index < nodes.size && reached(player, nodes[index])) {
            index++
        }
        if (index >= nodes.size) {
            lockedNode = null
            return null
        }
        lockedNode = nodes[index].pos
        return index
    }

    /** Closest node by XZ with floor proximity as a tiebreaker. */
    private fun nearestNodeIndex(player: LocalPlayer, nodes: List<PathNode>): Int {
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
        return best
    }

    /**
     * Where the head looks: the first waypoint far enough away to give a stable bearing, sighted at
     * eye level rather than at its floor. Node 0 is the cell the path started from, so it is never a
     * look target while anything follows it — aiming at your own feet is what makes the view snap.
     */
    private fun aimPoint(player: LocalPlayer, nodes: List<PathNode>, followIndex: Int): Vec3 {
        val last = nodes.lastIndex
        var index = followIndex.coerceAtLeast(if (last > 0) 1 else 0)
        while (index < last && horizontalDist(player, nodes[index]) < AIM_MIN_XZ) {
            index++
        }
        val node = nodes[index]
        val centre = StandingPositions.nodeCentre(node.pos, node.floorY)
        return Vec3(centre.x, centre.y + player.eyeHeight, centre.z)
    }

    private fun reached(player: LocalPlayer, node: PathNode): Boolean {
        if (player.blockPosition() == node.pos) return true
        return horizontalDist(player, node) <= NODE_REACH_XZ
    }

    private fun horizontalDist(player: LocalPlayer, node: PathNode): Double {
        val cx = node.pos.x + 0.5
        val cz = node.pos.z + 0.5
        return hypot(player.x - cx, player.z - cz)
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
