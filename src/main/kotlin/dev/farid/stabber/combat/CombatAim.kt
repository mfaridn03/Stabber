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
 * tracking them, and only glides back toward the centre of their hitbox once strafing pushes that
 * hitbox close to the edge of the view. Corrections are closed-loop through [RotationController],
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

    /** Cursor beyond this fraction — near the apparent edge — triggers a glide back to centre. */
    const val HOVER_EXIT: Double = 0.70

    /** Beyond this many apparent radii off centre the correction becomes a fast reacquire glance. */
    const val REACQUIRE_RATIO: Double = 1.0

    /** Glide speed bounds, deg/s, rolled once per correction event. */
    const val RECENTER_MIN_RATE_DEG_PER_SEC: Double = 260.0
    const val RECENTER_MAX_RATE_DEG_PER_SEC: Double = 400.0

    /** Speed, deg/s, when the cursor has lost the target entirely. */
    const val REACQUIRE_RATE_DEG_PER_SEC: Double = 540.0

    /** Glides may carry past the centre by up to this fraction of the apparent radius, rolled per event. */
    const val OVERSHOOT_MAX_FRACTION: Double = 0.15

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
     * False right after arming so the first update snaps onto the target, then true whenever the
     * cursor rests inside [HOVER_ENTER].
     */
    private var hovering = false

    /** Per-correction-event rolls: turn speed and how far to carry past the centre. */
    private var eventRate = REACQUIRE_RATE_DEG_PER_SEC
    private var overshootFraction = 0.0

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

            if (hovering) {
                if (offRatio >= HOVER_EXIT) {
                    beginCorrection(offRatio > REACQUIRE_RATIO)
                } else {
                    val now = System.nanoTime()
                    if (now >= nudgeDueNanos) {
                        // Tiny drifts while resting on the target; RotationController's wander animates them.
                        yawRequest = player.yRot + symmetric(NUDGE_MAX_YAW_DEG).toFloat()
                        rate = NUDGE_RATE_DEG_PER_SEC
                        armNextNudge(now)
                    }
                }
            } else if (offRatio <= HOVER_ENTER) {
                hovering = true
                armNextNudge(System.nanoTime())
            }

            if (!hovering) {
                val overshootDeg = sign(yawError) * overshootFraction * apparentRadius
                yawRequest = Mth.wrapDegrees(yawToCentre + overshootDeg.toFloat())
                rate = eventRate
            }
        }

        if (yawRequest == null && pitchRequest == null) return
        RotationController.rotateTo(yawRequest, pitchRequest, rate)
    }

    fun reset() {
        hovering = false
        eventRate = REACQUIRE_RATE_DEG_PER_SEC
        overshootFraction = 0.0
        nudgeDueNanos = 0L
    }

    /** Rolls the speed and overshoot for one correction, fast when the cursor lost the target. */
    private fun beginCorrection(reacquire: Boolean) {
        hovering = false
        eventRate = if (reacquire) {
            REACQUIRE_RATE_DEG_PER_SEC
        } else {
            uniform(RECENTER_MIN_RATE_DEG_PER_SEC, RECENTER_MAX_RATE_DEG_PER_SEC)
        }
        overshootFraction = uniform(0.0, OVERSHOOT_MAX_FRACTION)
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
