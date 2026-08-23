package dev.farid.stabber.combat

import dev.farid.stabber.client.rotation.RotationController
import dev.farid.stabber.client.target.TargetManager
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Locks the view onto the fight target. Every render frame the aim point is derived from the
 * target's bounding box slid onto its partial-tick interpolated position, so the requested angles
 * track the same smooth motion the renderer draws, and the view is driven there as fast as the
 * GCD-safe delivery in [RotationController] allows.
 *
 * A freshly acquired target is not tracked immediately: aiming starts only after a per-acquisition
 * sampled human reaction delay. The aim point itself is a gaussian-weighted offset from the
 * target's centre that slowly wanders via per-axis drift noise, instead of pinning dead centre.
 * Corrections run in two phases — a ballistic flick that eases out onto an apex placed slightly
 * past the target and then settles back, switching to a soft lagged tracking filter once close,
 * and back to a fresh flick if the target escapes. While the view
 * error sits inside a sampled deadzone no corrections are issued at all; the deadzone tightens
 * for a short window after each synthetic click so swings still land on target.
 */
object CombatAim {

    /** Lower bound of the sampled reaction delay before tracking starts, ms. */
    const val REACTION_MIN_MS: Double = 120.0

    /** Upper bound of the sampled reaction delay before tracking starts, ms. */
    const val REACTION_MAX_MS: Double = 250.0

    /** Lower bound of the sampled hold deadzone, degrees of aim error ignored while idle. */
    const val HOLD_DEADZONE_MIN_DEG: Double = 0.8

    /** Upper bound of the sampled hold deadzone, degrees. */
    const val HOLD_DEADZONE_MAX_DEG: Double = 1.5

    /** Lower bound of the sampled attack deadzone used right after a synthetic click. */
    const val ATTACK_DEADZONE_MIN_DEG: Double = 0.35

    /** Upper bound of the sampled attack deadzone, degrees. */
    const val ATTACK_DEADZONE_MAX_DEG: Double = 0.65

    /** How long after a synthetic click the narrower attack deadzone stays active, ms. */
    const val ATTACK_WINDOW_MS: Double = 350.0

    /** Lower bound of the sampled flick speed cap, degrees per second. */
    const val FLICK_SPEED_MIN_DEG_PER_S: Double = 240.0

    /** Upper bound of the sampled flick speed cap, degrees per second. */
    const val FLICK_SPEED_MAX_DEG_PER_S: Double = 520.0

    /** Proportional gain turning remaining angle into the flick speed cap (ease-out decay). */
    const val FLICK_GAIN_PER_S: Double = 14.0

    /** Floor for the proportional flick cap so distant starts still move briskly. */
    const val FLICK_MIN_STEP_DEG_PER_S: Double = 40.0

    /** Angular distance below which a flick hands over to tracking, degrees. */
    const val TRACK_ENTER_DEG: Double = 8.0

    /** Angular distance above which tracking gives up and re-flicks, degrees. */
    const val TRACK_EXIT_DEG: Double = 18.0

    /** First-order tracking bandwidth; higher follows the target more tightly. */
    const val TRACK_BANDWIDTH_PER_S: Double = 12.0

    /** Hard cap on tracking-phase rotation speed, degrees per second. */
    const val TRACK_MAX_STEP_DEG_PER_S: Double = 120.0

    /** Lower bound of the sampled yaw speed multiplier over pitch; horizontal sweeps are faster. */
    const val YAW_BIAS_MIN: Double = 1.35

    /** Upper bound of the sampled yaw speed multiplier over pitch. */
    const val YAW_BIAS_MAX: Double = 1.75

    /** Lower bound of the sampled deadzone widening along yaw. */
    const val DEADZONE_YAW_SCALE_MIN: Double = 1.15

    /** Upper bound of the sampled deadzone widening along yaw. */
    const val DEADZONE_YAW_SCALE_MAX: Double = 1.45

    /** Lower bound of the overshoot, as a fraction of the flick's initial angular distance. */
    const val OVERSHOOT_MIN_FRACTION: Double = 0.04

    /** Upper bound of the overshoot fraction; real flicks pass their mark by a few degrees. */
    const val OVERSHOOT_MAX_FRACTION: Double = 0.10

    /** Hard ceiling on overshoot regardless of flick size, degrees. */
    const val OVERSHOOT_MAX_DEG: Double = 6.0

    /** Flicks shorter than this skip the overshoot entirely, degrees. */
    const val OVERSHOOT_MIN_DISTANCE_DEG: Double = 20.0

    /** Reference index of difficulty at which the flick ceiling applies at full sampled speed. */
    const val FITTS_REF_ID: Double = 4.0

    /** Peak wander of the tracking bandwidth, as a fraction of its base value. */
    const val TRACK_BANDWIDTH_WANDER: Double = 0.25

    /** Floor for the wandering tracking bandwidth, per second. */
    const val TRACK_BANDWIDTH_MIN_PER_S: Double = 8.0

    /** Ceiling for the wandering tracking bandwidth, per second. */
    const val TRACK_BANDWIDTH_MAX_PER_S: Double = 20.0

    /** Sigma of the gaussian anchor offset, as a fraction of the hitbox half-extent per axis. */
    const val AIM_SIGMA_OF_HALF_EXTENT: Double = 0.35

    /** Margin kept from the hitbox edge for any combined aim offset, blocks. */
    const val AIM_EDGE_MARGIN_BLOCKS: Double = 0.05

    /** Peak total wander amplitude of the drifting offset, blocks. */
    const val DRIFT_AMPLITUDE_BLOCKS: Double = 0.06

    /** Base temporal frequency of the drift wander, Hz. */
    const val DRIFT_FREQUENCY_HZ: Double = 0.35

    /** Number of sine components summed per drift axis. */
    private const val DRIFT_WAVES_PER_AXIS: Int = 3

    /** Aim correction phases: ballistic approach then soft pursuit. */
    private enum class Phase { FLICK, TRACK }

    /** Entity id whose acquisition started the current reaction window; none when reset. */
    private var acquiredTargetId: Int? = null

    /** When [acquiredTargetId] was first seen. */
    private var acquiredNanos = 0L

    /** Sampled delay applied after acquisition. */
    private var reactionDelayNanos = 0L

    /** Per-acquisition error tolerance while lazily holding aim, degrees. */
    private var holdDeadzoneDeg = (HOLD_DEADZONE_MIN_DEG + HOLD_DEADZONE_MAX_DEG) / 2.0

    /** Per-acquisition tightened tolerance while attacking, degrees. */
    private var attackDeadzoneDeg = (ATTACK_DEADZONE_MIN_DEG + ATTACK_DEADZONE_MAX_DEG) / 2.0

    /** Per-acquisition yaw speed multiplier over pitch, applied to every rotation request. */
    private var yawSpeedBias = (YAW_BIAS_MIN + YAW_BIAS_MAX) / 2.0

    /** Per-acquisition widening of the deadzone along yaw, where horizontal error matters less. */
    private var deadzoneYawScale = (DEADZONE_YAW_SCALE_MIN + DEADZONE_YAW_SCALE_MAX) / 2.0

    private var phase = Phase.FLICK

    /** Sampled ceiling for the current flick, degrees per second. */
    private var flickSpeedDegPerSec =
        (FLICK_SPEED_MIN_DEG_PER_S + FLICK_SPEED_MAX_DEG_PER_S) / 2.0

    /** Set when a flick is armed and still needs its overshoot sampled from live angles. */
    private var flickNeedsSetup = true

    /** Angular distance the current flick aims past the target by, degrees. */
    private var overshootDeg = 0.0

    /** Unit approach direction of the current flick, used to place the apex past the target. */
    private var overshootDirYaw = 0.0f
    private var overshootDirPitch = 0.0f

    /** Apex distance of the previous frame; growth means the view passed the mark. */
    private var prevApexDistance = Double.NaN

    /** Lagged reference angles the tracking filter chases instead of the live ideal. */
    private var trackYaw = 0.0f
    private var trackPitch = 0.0f

    /** Previous aim frame's timestamp for filter integration; zero forces a sane default dt. */
    private var lastAimNanos = 0L

    /** Gaussian anchor offset from the hitbox centre sampled at acquisition, blocks per axis. */
    private val anchorOffset = DoubleArray(3)

    /** Per-axis drift wanderers resampled at acquisition; null until first acquisition. */
    private val axisDrifts = arrayOfNulls<AxisDrift>(3)

    /** Wanderer modulating the tracking bandwidth so pursuit tightens and loosens organically. */
    private var trackBandwidthDrift = AxisDrift(Random.nextLong(), TRACK_BANDWIDTH_WANDER)

    fun update(minecraft: Minecraft, partialTick: Float) {
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        if (!TargetManager.validate(level)) return
        val target = TargetManager.target ?: return

        // A new acquisition restarts the reaction window and resamples the per-fight constants;
        // nothing tracks until the reaction delay elapses.
        val nowNanos = System.nanoTime()
        if (target.id != acquiredTargetId) {
            acquiredTargetId = target.id
            acquiredNanos = nowNanos
            reactionDelayNanos =
                (uniform(REACTION_MIN_MS, REACTION_MAX_MS) * 1.0e6).toLong()
            holdDeadzoneDeg = uniform(HOLD_DEADZONE_MIN_DEG, HOLD_DEADZONE_MAX_DEG)
            attackDeadzoneDeg = uniform(ATTACK_DEADZONE_MIN_DEG, ATTACK_DEADZONE_MAX_DEG)
            yawSpeedBias = uniform(YAW_BIAS_MIN, YAW_BIAS_MAX)
            deadzoneYawScale = uniform(DEADZONE_YAW_SCALE_MIN, DEADZONE_YAW_SCALE_MAX)
            sampleAimOffset(target)
            trackBandwidthDrift = AxisDrift(Random.nextLong(), TRACK_BANDWIDTH_WANDER)
            armFlick()
            lastAimNanos = 0L
        }
        if (nowNanos - acquiredNanos < reactionDelayNanos) return

        aimAt(player, target, partialTick, nowNanos)
    }

    /** Forgets the current acquisition so the next frame samples fresh humanization constants. */
    fun reset() {
        acquiredTargetId = null
        reactionDelayNanos = 0L
        lastAimNanos = 0L
    }

    private fun aimAt(
        player: LocalPlayer,
        target: LivingEntity,
        partialTick: Float,
        nowNanos: Long,
    ) {

        val eye = player.getEyePosition(partialTick)
        // boundingBox only updates once per tick; slide it onto the render-frame interpolated
        // position so the locked angles track what is actually drawn.
        val interp = target.getPosition(partialTick)
        var centre: Vec3 = target.boundingBox
            .move(interp.x - target.x, interp.y - target.y, interp.z - target.z)
            .center

        // Gaussian-weighted aim point that slowly wanders instead of pinning dead centre.
        val bb = target.boundingBox
        val elapsedSeconds = (nowNanos - acquiredNanos) / 1.0e9
        val halfExtents = doubleArrayOf(bb.xsize / 2.0, bb.ysize / 2.0, bb.zsize / 2.0)
        for (axis in 0..2) {
            val limit = maxOf(halfExtents[axis] - AIM_EDGE_MARGIN_BLOCKS, 0.0)
            val drift = axisDrifts[axis]?.at(elapsedSeconds) ?: 0.0
            val offset = (anchorOffset[axis] + drift).coerceIn(-limit, limit)
            centre = when (axis) {
                0 -> centre.add(offset, 0.0, 0.0)
                1 -> centre.add(0.0, offset, 0.0)
                else -> centre.add(0.0, 0.0, offset)
            }
        }

        val dx = centre.x - eye.x
        val dy = centre.y - eye.y
        val dz = centre.z - eye.z
        val yaw = Math.toDegrees(Mth.atan2(dz, dx)).toFloat() - 90.0f
        val pitch = (-Math.toDegrees(Mth.atan2(dy, sqrt(dx * dx + dz * dz)))).toFloat()

        // Lazy hold: while the view sits inside the tolerance band no corrections are issued at
        // all. Right after a click the band tightens so swings land on target, then widens again.
        // The yaw zone runs wider — horizontal misses bother a human less than vertical ones.
        // Mid-flick the hold deadzone is suspended so a flick is never cancelled while sweeping
        // through its own aim point; attack precision still applies.
        val yawError = abs(Mth.wrapDegrees(yaw - player.yRot).toDouble())
        val pitchError = abs((pitch - player.xRot).toDouble())
        val attacking = AttackController.recentlyAttacked(nowNanos, ATTACK_WINDOW_MS)
        if (phase == Phase.TRACK || attacking) {
            val zoneWidth =
                (if (attacking) attackDeadzoneDeg else holdDeadzoneDeg)
            if (yawError <= zoneWidth * deadzoneYawScale && pitchError <= zoneWidth) {
                RotationController.cancel()
                return
            }
        }

        // Two-phase correction: a ballistic flick eases out onto an apex placed slightly past
        // the target, hands over to lagged tracking when the mark is crossed, and re-flicks if
        // the target escapes the tracking band.
        var dtSeconds = if (lastAimNanos == 0L) 1.0 / 60.0 else (nowNanos - lastAimNanos) / 1.0e9
        dtSeconds = dtSeconds.coerceIn(0.001, 0.25)
        lastAimNanos = nowNanos

        val distance = sqrt(yawError * yawError + pitchError * pitchError)
        if (phase == Phase.FLICK) {
            if (flickNeedsSetup) {
                setupFlick(player.yRot, player.xRot, yaw, pitch, attacking)
            }

            // The flick aims at an apex offset past the ideal point along the approach direction.
            val apexYaw = Mth.wrapDegrees(yaw + overshootDirYaw * overshootDeg.toFloat())
            val apexPitch =
                Mth.clamp(pitch + overshootDirPitch * overshootDeg.toFloat(), -90.0f, 90.0f)
            val apexYawError = abs(Mth.wrapDegrees(apexYaw - player.yRot).toDouble())
            val apexPitchError = abs((apexPitch - player.xRot).toDouble())
            val apexDistance = sqrt(apexYawError * apexYawError + apexPitchError * apexPitchError)

            // Once the view stops closing on the apex it has crossed the mark: settle back.
            val crossedApex = !prevApexDistance.isNaN() && apexDistance >= prevApexDistance
            prevApexDistance = apexDistance

            if (!crossedApex && apexDistance > TRACK_ENTER_DEG) {
                // Fitts-inspired pacing: the harder the aim (big angle onto a small apparent
                // target), the lower the speed ceiling, so time-to-target grows with the log of
                // distance over size instead of staying flat.
                val halfDiagonal =
                    sqrt(
                        halfExtents[0] * halfExtents[0] +
                            halfExtents[1] * halfExtents[1] +
                            halfExtents[2] * halfExtents[2],
                    )
                val viewDistance = sqrt(dx * dx + dy * dy + dz * dz)
                val angularWidthDeg =
                    Math.toDegrees(atan2(halfDiagonal, maxOf(viewDistance, 0.5)))
                val difficultyIndex =
                    if (angularWidthDeg < 1.0e-3) {
                        FITTS_REF_ID
                    } else {
                        2.0 * apexDistance / angularWidthDeg
                    }
                val fittsCeiling =
                    flickSpeedDegPerSec / sqrt(maxOf(difficultyIndex / FITTS_REF_ID, 1.0))

                val stepCap = (apexDistance * FLICK_GAIN_PER_S)
                    .coerceIn(
                        FLICK_MIN_STEP_DEG_PER_S,
                        minOf(flickSpeedDegPerSec, fittsCeiling),
                    )
                RotationController.rotateTo(apexYaw, apexPitch, stepCap * yawSpeedBias, stepCap)
                return
            }
            enterTrack(player.yRot, player.xRot)
        } else if (distance > TRACK_EXIT_DEG) {
            armFlick()
        }

        // Lagged tracking filter — also serves as the settle-back after an overshoot. Its
        // bandwidth wanders so pursuit tightens and loosens like a person's attention.
        val bandwidth =
            (TRACK_BANDWIDTH_PER_S * (1.0 + trackBandwidthDrift.at(elapsedSeconds)))
                .coerceIn(TRACK_BANDWIDTH_MIN_PER_S, TRACK_BANDWIDTH_MAX_PER_S)
        val alpha = (1.0 - exp(-bandwidth * dtSeconds)).toFloat()
        trackYaw += Mth.wrapDegrees(yaw - trackYaw) * alpha
        trackPitch += (pitch - trackPitch) * alpha
        RotationController.rotateTo(
            trackYaw,
            trackPitch,
            TRACK_MAX_STEP_DEG_PER_S * yawSpeedBias,
            TRACK_MAX_STEP_DEG_PER_S,
        )
    }

    /** Arms a fresh ballistic approach with a newly sampled flick speed. */
    private fun armFlick() {
        phase = Phase.FLICK
        flickNeedsSetup = true
        prevApexDistance = Double.NaN
        flickSpeedDegPerSec =
            uniform(FLICK_SPEED_MIN_DEG_PER_S, FLICK_SPEED_MAX_DEG_PER_S)
    }

    /**
     * Samples the current flick's apex from live angles: short flicks and active attacks go
     * straight at the target, everything else aims past it by a fraction of the approach angle.
     */
    private fun setupFlick(
        viewYaw: Float,
        viewPitch: Float,
        idealYaw: Float,
        idealPitch: Float,
        attacking: Boolean,
    ) {
        flickNeedsSetup = false
        val dyaw = Mth.wrapDegrees(idealYaw - viewYaw).toDouble()
        val dpitch = (idealPitch - viewPitch).toDouble()
        val initialDistance = sqrt(dyaw * dyaw + dpitch * dpitch)
        if (attacking || initialDistance < OVERSHOOT_MIN_DISTANCE_DEG) {
            overshootDeg = 0.0
            return
        }
        overshootDeg = (initialDistance * uniform(OVERSHOOT_MIN_FRACTION, OVERSHOOT_MAX_FRACTION))
            .coerceAtMost(OVERSHOOT_MAX_DEG)
        overshootDirYaw = (dyaw / initialDistance).toFloat()
        overshootDirPitch = (dpitch / initialDistance).toFloat()
    }

    /** Starts lagged tracking from where the view actually is so the handover never jumps. */
    private fun enterTrack(viewYaw: Float, viewPitch: Float) {
        phase = Phase.TRACK
        trackYaw = viewYaw
        trackPitch = viewPitch
    }

    /** Resamples the gaussian anchor offset and the per-axis drift wanderers. */
    private fun sampleAimOffset(target: LivingEntity) {
        val bb = target.boundingBox
        val halfExtents =
            doubleArrayOf(bb.xsize / 2.0, bb.ysize / 2.0, bb.zsize / 2.0)
        for (axis in 0..2) {
            // Sigma scales with the hitbox so a pig gets gentler offsets than an iron golem.
            val limit = maxOf(halfExtents[axis] - AIM_EDGE_MARGIN_BLOCKS, 0.0)
            anchorOffset[axis] =
                (halfExtents[axis] * AIM_SIGMA_OF_HALF_EXTENT * gaussian()).coerceIn(-limit, limit)
            axisDrifts[axis] = AxisDrift(Random.nextLong(), DRIFT_AMPLITUDE_BLOCKS)
        }
    }

    /** Standard normal via Box-Muller. */
    private fun gaussian(): Double {
        var u = Math.random()
        if (u < 1.0e-9) u = 1.0e-9
        return sqrt(-2.0 * ln(u)) * cos(2.0 * Math.PI * Math.random())
    }

    private fun uniform(min: Double, max: Double): Double = min + Math.random() * (max - min)

    /**
     * Smooth pseudo-random wander in [-peakAmplitude, peakAmplitude]: a fixed set of detuned sine
     * waves whose phases and frequency multipliers are drawn from a per-acquisition seed. Cheap,
     * continuous everywhere, and never repeats within a fight.
     */
    private class AxisDrift(seed: Long, private val peakAmplitude: Double) {
        private data class Wave(val omega: Double, val amplitude: Double, val phase: Double)

        private val waves = Array(DRIFT_WAVES_PER_AXIS) { index ->
            val random = Random(seed + index)
            Wave(
                omega = 2.0 * Math.PI * DRIFT_FREQUENCY_HZ *
                    (index + 1) * (0.7 + 0.6 * random.nextDouble()),
                amplitude = (peakAmplitude / DRIFT_WAVES_PER_AXIS) *
                    (0.6 + 0.4 * random.nextDouble()),
                phase = random.nextDouble() * 2.0 * Math.PI,
            )
        }

        fun at(seconds: Double): Double {
            var sum = 0.0
            for (wave in waves) {
                sum += wave.amplitude * sin(wave.omega * seconds + wave.phase)
            }
            return sum
        }
    }
}
