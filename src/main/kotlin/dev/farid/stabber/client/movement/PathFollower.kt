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
    /** Degrees per second while path-following: a brisk head turn, not an instant snap. */
    private const val TURN_RATE_DEG_PER_SEC = 180.0
    /** Degrees per second while acquiring a nearby target: quicker than travel, still human. */
    private const val TARGET_AIM_RATE_DEG_PER_SEC = 540.0
    /** Window over which the view slides off the path carrot and onto the target. */
    private const val TARGET_AIM_BLEND_MS = 400.0
    private const val STUCK_SPEED_EPS = 0.01
    private const val STUCK_TICKS = 8

    /** Blocks of sideways correction per block of offset from the path line. */
    private const val CROSSTRACK_GAIN = 1.0
    /** Damping on the offset's rate of change, so the correction does not overshoot into a weave. */
    private const val CROSSTRACK_DAMPING = 3.0
    /** Caps the correction at 45 degrees off the segment. */
    private const val MAX_LATERAL = 1.0

    /** Boundaries at which the desired heading starts recruiting a strafe key, then drops forward. */
    private const val STRAFE_ENTER_DEGREES = 22.5
    private const val STRAFE_ONLY_DEGREES = 67.5
    /** Deadband around those boundaries; without it the keys chatter while sitting on one. */
    private const val STRAFE_HYSTERESIS = 8.0

    /** Low-pass bandwidth for the heading error fed to the strafe quantiser. */
    private const val HEADING_ERROR_SMOOTHING_PER_SEC = 8.0
    /** Consecutive ticks the heading must agree on a side before the strafe keys swap sides. */
    private const val SIDE_FLIP_TICKS = 3
    private const val TICK_SECONDS = 0.05

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
    private var lastCrossTrack: Double? = null
    private var offPathTicks = 0
    /** Signed strafe bucket carried between ticks so [STRAFE_HYSTERESIS] has something to hold against. */
    private var steerBucket = 0

    /** Low-passed heading error; reseeded whenever the path geometry changes under us. */
    private var smoothedRelative: Double? = null

    /** Strafe side currently driving the keys; flipping it takes [SIDE_FLIP_TICKS] confirming ticks. */
    private var appliedSide = 0
    private var sideStreak = 0

    /** When the current run of target-acquisition frames began, or -1 while tracking the path. */
    private var targetAimStartedMs = -1L

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

        syncToPath(player, nodes)
        val fix = PathProgress.project(nodes, player.x, player.y, player.z, progressIndex, NODE_REACH_XZ)
        if (fix == null) {
            followSingleNode(player, nodes.first())
            return
        }

        progressIndex = fix.index
        val node = nodes[fix.index + 1]
        lockedNode = node.pos
        updateOffPath(fix.crossTrack)

        val remaining = PathProgress.remainingLength(nodes, fix)
        val target = TargetManager.target
        // Interaction range is several blocks; keep tracking the path until the last leg so a nearby
        // target does not steal the look while jump/walk nodes are still ahead.
        if (target != null && onFinalLeg(nodes, fix) && canAttack(player, target)) {
            MovementController.apply(forward = false)
            stuckTicks = 0
            rememberPos(player)
            return
        }

        // Past the final waypoint with the target still out of reach: nothing left to steer along, so
        // stop rather than keep walking forward off the end of the path.
        if (remaining < AIM_HOLD_XZ) {
            releaseControls()
            return
        }

        val carrot = PathProgress.carrot(nodes, fix, LOOKAHEAD)

        val travel = travelDirection(fix, player, carrot)
        val relative = filterRelative(Mth.degreesDifference(player.yRot, travel).toDouble())
        steerBucket = steerBucket(relative, steerBucket)
        val forward = abs(steerBucket) < 2
        val strafeLeft = steerBucket < 0
        val strafeRight = steerBucket > 0

        MovementController.apply(
            forward = forward,
            left = strafeLeft,
            right = strafeRight,
            jump = shouldJump(player, node),
        )

        updateStuck(player)
        if (stuckTicks >= STUCK_TICKS && player.onGround()) {
            MovementController.requestJump()
            stuckTicks = 0
        }
    }

    /**
     * Refreshes the look target from interpolated positions. Called every render frame so the
     * camera tracks the carrot (or attack target) between ticks.
     */
    fun updateAim(minecraft: Minecraft, partialTick: Float) {
        if (!following) return
        val player = minecraft.player ?: return
        if (!PathfindingController.active) return

        val nodes = PathfindingController.path.nodes
        if (nodes.isEmpty()) return

        val pos = player.getPosition(partialTick)
        val eyeY = player.eyeHeight.toDouble()
        val target = TargetManager.target

        val fix = PathProgress.project(nodes, pos.x, pos.y, pos.z, progressIndex, NODE_REACH_XZ)
        if (fix == null) {
            if (target != null && canAttack(player, target)) {
                RotationController.lookAt(
                    player,
                    blendToTarget(null, target, partialTick),
                    TARGET_AIM_RATE_DEG_PER_SEC,
                    partialTick,
                )
                return
            }
            targetAimStartedMs = -1L
            val node = nodes.first()
            val centre = StandingPositions.nodeCentre(node.pos, node.floorY)
            if (hypot(pos.x - centre.x, pos.z - centre.z) < AIM_HOLD_XZ) return
            RotationController.lookAt(player, centre.add(0.0, eyeY, 0.0), TURN_RATE_DEG_PER_SEC, partialTick)
            return
        }

        if (target != null && onFinalLeg(nodes, fix) && canAttack(player, target)) {
            val carrot = PathProgress.carrot(nodes, fix, LOOKAHEAD).add(0.0, eyeY, 0.0)
            RotationController.lookAt(
                player,
                blendToTarget(carrot, target, partialTick),
                TARGET_AIM_RATE_DEG_PER_SEC,
                partialTick,
            )
            return
        }
        targetAimStartedMs = -1L

        val remaining = PathProgress.remainingLength(nodes, fix)
        if (remaining < AIM_HOLD_XZ) return

        val carrot = PathProgress.carrot(nodes, fix, LOOKAHEAD)
        if (hypot(pos.x - carrot.x, pos.z - carrot.z) >= AIM_HOLD_XZ) {
            RotationController.lookAt(player, carrot.add(0.0, eyeY, 0.0), TURN_RATE_DEG_PER_SEC, partialTick)
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
        lastCrossTrack = null
        smoothedRelative = null
        appliedSide = 0
        sideStreak = 0
        offPathTicks = 0
        offPath = false
        steerBucket = 0
        targetAimStartedMs = -1L
    }

    private fun releaseControls() {
        MovementController.release()
        RotationController.cancel()
        targetAimStartedMs = -1L
    }

    /**
     * Slides the aim point from [from] (the path carrot) onto the target's eyes across
     * [TARGET_AIM_BLEND_MS], so picking up a nearby target reads as a deliberate glance rather
     * than an instant snap when the final leg begins.
     */
    private fun blendToTarget(from: Vec3?, target: LivingEntity, partialTick: Float): Vec3 {
        val eyes = target.getEyePosition(partialTick)
        val started = targetAimStartedMs
        if (started < 0) {
            targetAimStartedMs = System.currentTimeMillis()
            return from ?: eyes
        }
        val t = ((System.currentTimeMillis() - started) / TARGET_AIM_BLEND_MS).coerceIn(0.0, 1.0)
        val eased = t * t * (3.0 - 2.0 * t)
        return from?.lerp(eyes, eased) ?: eyes
    }

    private fun onFinalLeg(nodes: List<PathNode>, fix: PathProgress.Fix): Boolean {
        return fix.index >= nodes.size - 2
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
        lastCrossTrack = null
        smoothedRelative = null
        appliedSide = 0
        sideStreak = 0
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
            lastCrossTrack = fix.crossTrack
            return yawToward(player, carrot)
        }

        // Null until seeded: treating a fresh measurement as "no previous offset" stops a republish
        // or reseed from manufacturing a huge derivative and slamming one strafe key for a few ticks.
        val derivative = fix.crossTrack - (lastCrossTrack ?: fix.crossTrack)
        lastCrossTrack = fix.crossTrack
        val lateral = (-(CROSSTRACK_GAIN * fix.crossTrack + CROSSTRACK_DAMPING * derivative))
            .coerceIn(-MAX_LATERAL, MAX_LATERAL)

        // Right of a heading (dirX, dirZ) is (-dirZ, dirX).
        val x = fix.dirX + -fix.dirZ * lateral
        val z = fix.dirZ + fix.dirX * lateral
        return Mth.wrapDegrees(Math.toDegrees(Mth.atan2(z, x)).toFloat() - 90.0f)
    }

    /**
     * Low-passes the heading error so single-tick spikes cannot reach the strafe quantiser: a corner
     * stepping the segment direction, a republished path, or the cross-track derivative reacting to
     * its own strafe output all arrive attenuated instead of as full-strength key flips.
     */
    private fun filterRelative(raw: Double): Double {
        val smoothed = smoothedRelative
        if (smoothed == null) {
            smoothedRelative = raw
            return raw
        }
        val alpha = 1.0 - Math.exp(-HEADING_ERROR_SMOOTHING_PER_SEC * TICK_SECONDS)
        val next = smoothed + Mth.degreesDifference(smoothed.toFloat(), raw.toFloat()) * alpha
        smoothedRelative = next
        return next
    }

    /**
     * Signed strafe bucket for a heading [relative] degrees off the player's facing: 0 forward only,
     * ±1 forward plus a strafe, ±2 strafe only. Backward is never pressed — the head is already
     * turning to close the gap, and reversing would trip the stuck detector.
     *
     * Boundaries are widened by [STRAFE_HYSTERESIS] against [previous] so a heading parked on one
     * does not flip the keys every tick. The side itself is stickier still: swapping A for D takes
     * [SIDE_FLIP_TICKS] consecutive ticks of disagreement, so an oscillating heading cannot spam
     * alternating strafe keys while the magnitude hysteresis holds the level steady.
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
        if (level == 0) {
            appliedSide = 0
            sideStreak = 0
            return 0
        }

        val rawSide = if (relative >= 0.0) 1 else -1
        val side = if (appliedSide == 0 || rawSide == appliedSide) {
            appliedSide = rawSide
            sideStreak = 0
            rawSide
        } else {
            sideStreak++
            if (sideStreak < SIDE_FLIP_TICKS) appliedSide
            else {
                appliedSide = rawSide
                sideStreak = 0
                rawSide
            }
        }
        return side * level
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
