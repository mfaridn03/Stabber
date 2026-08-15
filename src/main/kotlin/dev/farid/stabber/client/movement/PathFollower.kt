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
 *
 * Look direction and travel direction are deliberately separate. The head tracks a carrot sliding
 * ahead along the path so corners are anticipated smoothly, while the feet track the path itself via
 * strafe keys, so aiming into a turn does not walk the player out of the corridor.
 */
object PathFollower {
    /** Covers a full 1x1 cell (corner is ~0.71 from centre). */
    private const val NODE_REACH_XZ = 0.75
    /** How far ahead along the path the head aims. */
    private const val LOOKAHEAD = 3.0
    /** Closer than this the bearing is meaningless, so hold the current view instead. */
    private const val AIM_HOLD_XZ = 0.5
    /** Degrees per frame while path-following: a brisk head turn, not an instant snap. */
    private const val TURN_MAX_STEP = 9.0
    private const val SPRINT_YAW_TOLERANCE = 20.0f
    /** Blocks of path left; below this the run-up is not worth the loss of turn authority. */
    private const val SPRINT_MIN_REMAINING = 3.0
    private const val STUCK_SPEED_EPS = 0.01
    private const val STUCK_TICKS = 8

    /** Blocks of sideways correction per block of offset from the path line. */
    private const val CROSSTRACK_GAIN = 1.0
    /** Damping on the offset's rate of change, so the correction does not overshoot into a weave. */
    private const val CROSSTRACK_DAMPING = 2.0
    /** Caps the correction at 45 degrees off the segment. */
    private const val MAX_LATERAL = 1.0

    /** Boundaries at which the desired heading starts recruiting a strafe key, then drops forward. */
    private const val STRAFE_ENTER_DEGREES = 22.5
    private const val STRAFE_ONLY_DEGREES = 67.5
    /** Deadband around those boundaries; without it the keys chatter while sitting on one. */
    private const val STRAFE_HYSTERESIS = 8.0

    /** Offset from the path line beyond which strafing home is fighting terrain, not tracking. */
    private const val OFF_PATH_XZ = 3.0
    private const val OFF_PATH_TICKS = 10

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

    /** The player has been too far off the path line for too long to steer back onto it. */
    var offPath: Boolean = false
        private set

    private var stuckTicks = 0
    private var lastX = 0.0
    private var lastZ = 0.0

    private var progressIndex = 0
    private var trackedNodes: List<PathNode>? = null
    private var lastCrossTrack = 0.0
    private var offPathTicks = 0
    /** Signed strafe bucket carried between ticks so [STRAFE_HYSTERESIS] has something to hold against. */
    private var steerBucket = 0

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
            resetProgress()
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
            resetProgress()
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

        syncToPath(player, nodes)
        val fix = PathProgress.project(nodes, player.x, player.z, progressIndex, NODE_REACH_XZ)
        if (fix == null) {
            followSingleNode(player, nodes.first())
            return
        }

        progressIndex = fix.index
        val node = nodes[fix.index + 1]
        lockedNode = node.pos
        updateOffPath(fix.crossTrack)

        // Past the final waypoint with the target still out of reach: nothing left to steer along, so
        // stop rather than keep walking forward off the end of the path.
        val remaining = PathProgress.remainingLength(nodes, fix)
        if (remaining < AIM_HOLD_XZ) {
            releaseControls()
            return
        }

        val carrot = PathProgress.carrot(nodes, fix, LOOKAHEAD)
        if (horizontalDist(player, carrot) >= AIM_HOLD_XZ) {
            RotationController.lookAt(player, carrot.add(0.0, player.eyeHeight.toDouble(), 0.0), TURN_MAX_STEP)
        }

        val travel = travelDirection(fix, player, carrot)
        val relative = Mth.degreesDifference(player.yRot, travel).toDouble()
        steerBucket = steerBucket(relative, steerBucket)
        val forward = abs(steerBucket) < 2
        val strafeLeft = steerBucket < 0
        val strafeRight = steerBucket > 0

        val yawError = abs(Mth.degreesDifference(player.yRot, yawToward(player, carrot)))
        val sprint = forward && yawError <= SPRINT_YAW_TOLERANCE && remaining > SPRINT_MIN_REMAINING

        MovementController.apply(
            forward = forward,
            left = strafeLeft,
            right = strafeRight,
            sprint = sprint,
            jump = shouldJump(player, node),
        )

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
        resetProgress()
    }

    private fun resetProgress() {
        lockedNode = null
        progressIndex = 0
        trackedNodes = null
        lastCrossTrack = 0.0
        offPathTicks = 0
        offPath = false
        steerBucket = 0
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
     * Re-seeds progress when a freshly computed path is published.
     *
     * Matching the old waypoint by position is not reliable: string pulling is greedy and
     * start-dependent, so a waypoint can vanish from the set even when the route through it is
     * unchanged. Projecting onto the new polyline finds the same place on the ground either way.
     */
    private fun syncToPath(player: LocalPlayer, nodes: List<PathNode>) {
        if (trackedNodes === nodes) return
        trackedNodes = nodes
        lastCrossTrack = 0.0
        steerBucket = 0
        progressIndex = if (nodes.size < 2) 0 else PathProgress.nearestSegment(nodes, player.x, player.z)
    }

    /** Degenerate one-node path: walk at it directly, there is no segment to track. */
    private fun followSingleNode(player: LocalPlayer, node: PathNode) {
        lockedNode = node.pos
        val centre = StandingPositions.nodeCentre(node.pos, node.floorY)
        if (horizontalDist(player, centre) < AIM_HOLD_XZ) {
            releaseControls()
            return
        }
        RotationController.lookAt(player, centre.add(0.0, player.eyeHeight.toDouble(), 0.0), TURN_MAX_STEP)
        MovementController.apply(forward = true, jump = shouldJump(player, node))
        updateStuck(player)
    }

    /**
     * Heading the feet should take: the segment direction, pushed back toward the path line in
     * proportion to how far off it the player has drifted.
     *
     * This is what lets the head aim past a corner without the body following it wide.
     */
    private fun travelDirection(fix: PathProgress.Fix, player: LocalPlayer, carrot: Vec3): Float {
        if (fix.dirX == 0.0 && fix.dirZ == 0.0) {
            lastCrossTrack = 0.0
            return yawToward(player, carrot)
        }

        val derivative = fix.crossTrack - lastCrossTrack
        lastCrossTrack = fix.crossTrack
        val lateral = (-(CROSSTRACK_GAIN * fix.crossTrack + CROSSTRACK_DAMPING * derivative))
            .coerceIn(-MAX_LATERAL, MAX_LATERAL)

        // Right of a heading (dirX, dirZ) is (-dirZ, dirX).
        val x = fix.dirX + -fix.dirZ * lateral
        val z = fix.dirZ + fix.dirX * lateral
        return Mth.wrapDegrees(Math.toDegrees(Mth.atan2(z, x)).toFloat() - 90.0f)
    }

    /**
     * Signed strafe bucket for a heading [relative] degrees off the player's facing: 0 forward only,
     * ±1 forward plus a strafe, ±2 strafe only. Backward is never pressed — the head is already
     * turning to close the gap, and reversing would trip the stuck detector.
     *
     * Boundaries are widened by [STRAFE_HYSTERESIS] against [previous] so a heading parked on one
     * does not flip the keys every tick.
     */
    private fun steerBucket(relative: Double, previous: Int): Int {
        val magnitude = abs(relative)
        val level = when (abs(previous)) {
            0 -> when {
                magnitude > STRAFE_ONLY_DEGREES + STRAFE_HYSTERESIS -> 2
                magnitude > STRAFE_ENTER_DEGREES + STRAFE_HYSTERESIS -> 1
                else -> 0
            }
            1 -> when {
                magnitude < STRAFE_ENTER_DEGREES - STRAFE_HYSTERESIS -> 0
                magnitude > STRAFE_ONLY_DEGREES + STRAFE_HYSTERESIS -> 2
                else -> 1
            }
            else -> if (magnitude < STRAFE_ONLY_DEGREES - STRAFE_HYSTERESIS) 1 else 2
        }
        if (level == 0) return 0
        return if (relative >= 0.0) level else -level
    }

    private fun updateOffPath(crossTrack: Double) {
        if (abs(crossTrack) > OFF_PATH_XZ) {
            offPathTicks++
        } else {
            offPathTicks = 0
        }
        offPath = offPathTicks >= OFF_PATH_TICKS
    }

    private fun horizontalDist(player: LocalPlayer, point: Vec3): Double {
        return hypot(player.x - point.x, player.z - point.z)
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
