package dev.farid.stabber.combat

import dev.farid.stabber.client.rotation.RotationController
import dev.farid.stabber.client.target.TargetManager
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Aims at the fight target the way a person does: the cursor rests on the opponent instead of
 * tracking them, and only the [adjusting] state — entered when the cursor drifts off the hover
 * band or loses the hitbox — glides it back toward an offset point near the centre of their
 * hitbox, never perfectly centred. Corrections are closed-loop through [RotationController],
 * whose smoothing and drift layers shape every glide.
 *
 * Geometry is angular: how far the view points off the hitbox centre is measured in units of the
 * hitbox's *apparent radius* (`atan(halfDiagonal / distance)`), so the same hysteresis band works
 * up close and far away.
 */
object CombatAim {

    /** Pitch clamps to this on either side of level; beyond it, extra height difference saturates. */
    const val PITCH_LIMIT_DEG: Double = 30.0

    /** Vertical angles within this band of level are treated as exactly level (pitch ~0 has max reach). */
    const val PITCH_DEADBAND_DEG: Double = 6.0

    /** A settled pitch is left alone until the error grows past this, giving rest between glances. */
    const val PITCH_REACQUIRE_DEG: Double = 0.6

    /** Degrees per second for pitch-only corrections; slower and gentler than yaw glides. */
    const val PITCH_ONLY_RATE_DEG_PER_SEC: Double = 120.0

    /** Cursor within this fraction of the apparent radius counts as centred (hovering). */
    const val HOVER_ENTER: Double = 0.25

    /** Cursor beyond this fraction — near the apparent edge — engages the adjust-aim state. */
    const val HOVER_EXIT: Double = 0.70

    /** Beyond this many apparent radii off centre the cursor has lost the hitbox entirely. */
    const val REACQUIRE_RATIO: Double = 1.0

    /** Settle-point offset bounds, in signed apparent radii, rolled once per adjustment. */
    const val ADJUST_OFFSET_MIN_FRACTION: Double = 0.10
    const val ADJUST_OFFSET_MAX_FRACTION: Double = 0.45

    /** Extra apparent-radii of slack before the adjust state counts its offset point as reached. */
    const val ADJUST_SETTLE_MARGIN: Double = 0.10

    /** Glide speed bounds, deg/s, rolled once per correction event. */
    const val RECENTER_MIN_RATE_DEG_PER_SEC: Double = 260.0
    const val RECENTER_MAX_RATE_DEG_PER_SEC: Double = 400.0

    /** Speed, deg/s, when the cursor has lost the target entirely. */
    const val REACQUIRE_RATE_DEG_PER_SEC: Double = 540.0

    /** Floor for the apparent radius, deg, so distant targets cannot make the ratios explode. */
    const val MIN_APPARENT_RADIUS_DEG: Double = 1.5

    /** Below this horizontal distance the bearing to the centre is meaningless; hold the view. */
    const val MIN_AIM_DISTANCE_XZ: Double = 1.0

    /** Micro-stutter cadence while hovering, seconds between sub-degree nudges. */
    const val NUDGE_MIN_INTERVAL_S: Double = 0.4
    const val NUDGE_MAX_INTERVAL_S: Double = 1.2

    /** Peak size of a hover nudge, deg. */
    const val NUDGE_MAX_YAW_DEG: Double = 0.18

    /** Degrees per second cap while playing a nudge out. */
    const val NUDGE_RATE_DEG_PER_SEC: Double = 60.0

    /**
     * The adjust-aim state. False right after arming so the first update engages it and snaps
     * onto the target; thereafter it turns on whenever the cursor drifts out of the hover band
     * or off the hitbox, and turns off once the settle point is reached.
     */
    private var adjusting = false

    /** Signed settle-point offset for the current adjustment, in apparent radii. */
    private var adjustOffsetRatio = 0.0

    /** Turn speed rolled when the current adjustment engaged, deg/s. */
    private var eventRate = REACQUIRE_RATE_DEG_PER_SEC

    /** Next nanoTime at which a hover nudge fires. */
    private var nudgeDueNanos = 0L

    fun update(minecraft: Minecraft, partialTick: Float) {
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        if (!TargetManager.validate(level)) return
        val target = TargetManager.target ?: return

        val eye = player.getEyePosition(partialTick)
        // boundingBox only updates once per tick; slide it onto the render-frame interpolated
        // position so requested angles track the same smooth motion as getEyePosition(partialTick).
        val interp = target.getPosition(partialTick)
        val box = target.boundingBox.move(interp.x - target.x, interp.y - target.y, interp.z - target.z)
        val centre = box.center
        val dx = centre.x - eye.x
        val dz = centre.z - eye.z
        val horiz = hypot(dx, dz)

        var yawRequest: Float? = null
        var pitchRequest: Float? = null
        var rate = PITCH_ONLY_RATE_DEG_PER_SEC

        // Pitch hugs zero wherever possible: a level ray keeps the full interaction range, and any
        // hitbox spanning the horizon already contains it. Only a box entirely below or entirely
        // above the horizon pulls pitch off zero (positive pitch = down in mojmap), saturating at
        // the clamp.
        val angTop = Math.toDegrees(atan2(box.maxY - eye.y, horiz))
        val angBottom = Math.toDegrees(atan2(box.minY - eye.y, horiz))
        val rawDown: Double = when {
            // Entire box below the horizon: dip down toward its top edge.
            angTop < 0.0 -> -angTop
            // Entire box above the horizon: raise up toward its bottom edge.
            angBottom > 0.0 -> -angBottom
            else -> 0.0
        }
        val desiredPitch = if (abs(rawDown) <= PITCH_DEADBAND_DEG) {
            0.0
        } else {
            rawDown.coerceIn(-PITCH_LIMIT_DEG, PITCH_LIMIT_DEG)
        }
        val pitchError = Mth.degreesDifference(player.xRot, desiredPitch.toFloat()).toDouble()
        if (abs(pitchError) > PITCH_REACQUIRE_DEG) {
            pitchRequest = desiredPitch.toFloat()
        }

        if (horiz > MIN_AIM_DISTANCE_XZ) {
            val yawToCentre = Math.toDegrees(atan2(dz, dx)).toFloat() - 90.0f
            val yawError = Mth.degreesDifference(player.yRot, yawToCentre).toDouble()
            val apparentRadius = apparentRadius(box, horiz)
            val offRatio = abs(yawError) / apparentRadius

            if (!adjusting && offRatio >= HOVER_EXIT) {
                // Cursor drifted to the hitbox edge or off it entirely: engage the adjust state
                // and roll where this glide settles — near the centre but never perfectly on it.
                adjusting = true
                adjustOffsetRatio = (if (Math.random() < 0.5) -1.0 else 1.0) *
                    uniform(ADJUST_OFFSET_MIN_FRACTION, ADJUST_OFFSET_MAX_FRACTION)
                eventRate = if (offRatio > REACQUIRE_RATIO) {
                    REACQUIRE_RATE_DEG_PER_SEC
                } else {
                    uniform(RECENTER_MIN_RATE_DEG_PER_SEC, RECENTER_MAX_RATE_DEG_PER_SEC)
                }
            } else if (adjusting && offRatio <= abs(adjustOffsetRatio) + ADJUST_SETTLE_MARGIN) {
                // Settle point reached: the adjust state releases and hover nudges resume.
                adjusting = false
                armNextNudge(System.nanoTime())
            }

            if (adjusting) {
                yawRequest = Mth.wrapDegrees(
                    yawToCentre + (adjustOffsetRatio * apparentRadius).toFloat(),
                )
                // If the target bolts mid-glide, finish this frame's request at reacquire speed.
                rate = if (offRatio > REACQUIRE_RATIO) REACQUIRE_RATE_DEG_PER_SEC else eventRate
            } else {
                val now = System.nanoTime()
                if (now >= nudgeDueNanos) {
                    // Tiny drifts while resting on the target; RotationController's wander animates them.
                    yawRequest = player.yRot + symmetric(NUDGE_MAX_YAW_DEG).toFloat()
                    rate = NUDGE_RATE_DEG_PER_SEC
                    armNextNudge(now)
                }
            }
        }

        if (yawRequest == null && pitchRequest == null) return
        RotationController.rotateTo(yawRequest, pitchRequest, rate)
    }

    fun reset() {
        adjusting = false
        adjustOffsetRatio = 0.0
        eventRate = REACQUIRE_RATE_DEG_PER_SEC
        nudgeDueNanos = 0L
    }

    private fun armNextNudge(now: Long) {
        nudgeDueNanos = now + (uniform(NUDGE_MIN_INTERVAL_S, NUDGE_MAX_INTERVAL_S) * 1.0e9).toLong()
    }

    /** Angular half-width of [box] seen from [horizDist] away, floored so ratios stay bounded. */
    private fun apparentRadius(box: AABB, horizDist: Double): Double {
        val halfDiagonal = 0.5 * hypot(box.xsize, box.zsize)
        val radius = Math.toDegrees(atan(halfDiagonal / horizDist))
        return radius.coerceAtLeast(MIN_APPARENT_RADIUS_DEG)
    }

    private fun uniform(min: Double, max: Double): Double = min + Math.random() * (max - min)

    private fun symmetric(maxAbs: Double): Double = (Math.random() * 2.0 - 1.0) * maxAbs
}
