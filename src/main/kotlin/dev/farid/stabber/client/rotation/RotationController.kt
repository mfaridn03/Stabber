package dev.farid.stabber.client.rotation

import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Rotates the player by feeding synthetic mouse deltas into [net.minecraft.client.MouseHandler],
 * so rotation still flows through vanilla's own turn path (sensitivity, invert options, tutorial
 * hook, vehicle passenger turning).
 *
 * Emitted deltas are always whole pixels with sub-pixel remainders carried across frames, matching
 * real mouse hardware: every resulting yaw/pitch delta is an integer multiple of the current
 * sensitivity step, keeping rotations on the grid that GCD-based anticheat checks expect.
 *
 * Each axis runs a three-stage pipeline, recomputed against the player's live rotation every frame:
 * the requested angle feeds an exponentially smoothed reference, so retargeting never jumps; the
 * remaining error commands a turn speed; and that speed is approached under an acceleration limit,
 * so glances ramp up and settle down instead of starting and stopping dead. Control stays
 * closed-loop: smooth-camera smoothing and rounding simply settle over the following frames.
 */
object RotationController {

    /** Maximum rotation applied per second when the caller does not specify one. */
    const val DEFAULT_MAX_STEP: Double = 30.0 * 60.0

    /** Below this many degrees an axis counts as reached. */
    private const val EPSILON: Double = 0.01

    // Feels too laggy while walking? Raise REFERENCE_GAIN_PER_SEC / STEERING_GAIN_PER_SEC.
    /** Below this residual speed (deg/s) a settled axis stops nudging. */
    private const val SETTLE_SPEED: Double = 0.5

    /** How fast the smoothed reference chases the requested angle; higher is tighter but jumpier. */
    private const val REFERENCE_GAIN_PER_SEC: Double = 30.0

    /** Turn speed commanded per degree of remaining reference error, in (deg/s) per degree. */
    private const val STEERING_GAIN_PER_SEC: Double = 8.0

    /** Acceleration limit that rounds off the start and end of every glance, deg/s^2. */
    private const val MAX_ACCEL_DEG_PER_SEC_SQ: Double = 6000.0

    /** Peak degrees of organic gaze wander layered onto each requested angle. */
    private const val DRIFT_YAW_DEG: Double = 0.45
    private const val DRIFT_PITCH_DEG: Double = 0.30

    /** Wander layer rates, Hz: a lazy sway plus a quicker flick. */
    private const val DRIFT_SLOW_HZ: Double = 0.35
    private const val DRIFT_FAST_HZ: Double = 1.30

    /** Relative strength of the quicker layer against the slow sway. */
    private const val DRIFT_FAST_WEIGHT: Double = 0.5

    /** Synthetic mouse delta, in the same units as MouseHandler's accumulated movement. */
    class Step(val dx: Double, val dy: Double)

    var targetYaw: Float? = null
        private set

    var targetPitch: Float? = null
        private set

    /** Degrees per second. */
    private var maxStep: Double = DEFAULT_MAX_STEP

    /** Drift clock, advanced only while rotating so wander resumes where it left off. */
    private var clock = 0.0

    private val yawAxis = Axis(DRIFT_YAW_DEG)
    private val pitchAxis = Axis(DRIFT_PITCH_DEG)

    /** Sub-pixel motion not yet emitted; real mice can only report whole-pixel deltas. */
    private var carryX = 0.0
    private var carryY = 0.0

    val isRotating: Boolean
        get() = targetYaw != null || targetPitch != null

    /**
     * Rotates toward [yaw] and/or [pitch]; a null component leaves that axis under user control.
     * [maxStepDegrees] caps how far each axis moves per second.
     */
    fun rotateTo(yaw: Float?, pitch: Float?, maxStepDegrees: Double = DEFAULT_MAX_STEP) {
        if (yaw == null && pitch == null) {
            cancel()
            return
        }
        targetYaw = yaw?.let { Mth.wrapDegrees(it) }
        targetPitch = pitch?.let { Mth.clamp(it, -90.0f, 90.0f) }
        maxStep = maxStepDegrees.coerceAtLeast(EPSILON)
    }

    fun rotateToYaw(yaw: Float, maxStepDegrees: Double = DEFAULT_MAX_STEP) {
        rotateTo(yaw, targetPitch, maxStepDegrees)
    }

    fun rotateToPitch(pitch: Float, maxStepDegrees: Double = DEFAULT_MAX_STEP) {
        rotateTo(targetYaw, pitch, maxStepDegrees)
    }

    /** Turns at the acceleration limit — as fast as the pipeline allows. */
    fun snapTo(yaw: Float?, pitch: Float?) {
        rotateTo(yaw, pitch, Double.MAX_VALUE)
    }

    fun lookAt(
        player: Entity,
        point: Vec3,
        maxStepDegrees: Double = DEFAULT_MAX_STEP,
        partialTick: Float = 1.0f,
    ) {
        lookFromTo(player.getEyePosition(partialTick), point, maxStepDegrees)
    }

    fun lookAt(
        player: Entity,
        target: Entity,
        atEyes: Boolean = true,
        maxStepDegrees: Double = DEFAULT_MAX_STEP,
        partialTick: Float = 1.0f,
    ) {
        val to = if (atEyes) target.getEyePosition(partialTick) else target.getPosition(partialTick)
        lookFromTo(player.getEyePosition(partialTick), to, maxStepDegrees)
    }

    fun cancel() {
        targetYaw = null
        targetPitch = null
        maxStep = DEFAULT_MAX_STEP
        yawAxis.reset()
        pitchAxis.reset()
        carryX = 0.0
        carryY = 0.0
    }

    /**
     * Returns the mouse delta that moves [player] one step toward the active target, or null when
     * idle. [degreesPerUnitX] and [degreesPerUnitY] are the signed degrees applied per unit of
     * accumulated mouse movement, so the caller owns sensitivity and the invert options.
     * [deltaSeconds] is the wall-clock duration of this frame; [maxStep] caps steady-state speed,
     * while profile shape comes from the reference filter and acceleration limit.
     *
     * Both components are whole pixels: sub-pixel motion is banked and released once it sums to a
     * pixel, so applied rotations stay integer multiples of the caller's per-unit scale.
     */
    fun consumeFrameDelta(
        player: Entity,
        degreesPerUnitX: Double,
        degreesPerUnitY: Double,
        deltaSeconds: Double,
    ): Step? {
        val yaw = targetYaw
        val pitch = targetPitch
        if (yaw == null && pitch == null) return null

        // Clamp hitches so a pause never produces a giant jump.
        val dt = deltaSeconds.coerceIn(0.0, 0.25)
        clock += dt

        // Axis.step yields signed degrees for this frame; convert to mouse-delta units so the
        // caller keeps ownership of sensitivity and the invert options.
        fun toMouseUnits(degrees: Double, degreesPerUnit: Double): Double {
            if (Math.abs(degreesPerUnit) < 1.0e-9) return 0.0
            return degrees / degreesPerUnit
        }

        // Snap to whole pixels, banking the remainder: vanilla multiplies the accumulated delta by
        // a fixed sensitivity factor, so integer deltas keep every rotation change an exact
        // multiple of that step — the grid real mouse input lands on.
        fun quantize(wanted: Double, carry: Double): Pair<Double, Double> {
            val total = wanted + carry
            val emitted = Math.rint(total)
            return emitted to total - emitted
        }

        var dx = 0.0
        if (yaw != null) {
            val deg = yawAxis.step(
                current = player.yRot.toDouble(),
                raw = yaw.toDouble(),
                wraps = true,
                dt = dt,
                time = clock,
                maxRate = maxStep,
            )
            val (emitted, rest) = quantize(toMouseUnits(deg, degreesPerUnitX), carryX)
            carryX = rest
            dx = emitted
            if (yawAxis.settled) {
                targetYaw = null
                carryX = 0.0
            }
        }

        var dy = 0.0
        if (pitch != null) {
            val deg = pitchAxis.step(
                current = player.xRot.toDouble(),
                raw = pitch.toDouble(),
                wraps = false,
                dt = dt,
                time = clock,
                maxRate = maxStep,
            )
            val (emitted, rest) = quantize(toMouseUnits(deg, degreesPerUnitY), carryY)
            carryY = rest
            dy = emitted
            if (pitchAxis.settled) {
                targetPitch = null
                carryY = 0.0
            }
        }

        if (!isRotating) {
            maxStep = DEFAULT_MAX_STEP
        }
        return Step(dx, dy)
    }

    /**
     * One axis of the turn pipeline: an exponentially smoothed reference chases the requested
     * angle, and the player is steered toward that reference at a speed commanded by the remaining
     * error and approached under the acceleration limit.
     *
     * The requested angle is first perturbed by layered slow sines ([driftAmplitudeDeg] peak), so
     * pursuit of it keeps the view from ever sitting perfectly still or sweeping at an exactly
     * constant rate. Phases are randomised per axis so yaw and pitch do not wander in lockstep.
     */
    private class Axis(private val driftAmplitudeDeg: Double) {
        /** Set for exactly one step, when the axis reaches its target. */
        var settled = false
            private set

        private val phaseSlow = Math.random() * 2.0 * PI
        private val phaseFast = Math.random() * 2.0 * PI

        private var ref: Double? = null
        private var vel = 0.0

        fun step(
            current: Double,
            raw: Double,
            wraps: Boolean,
            dt: Double,
            time: Double,
            maxRate: Double,
        ): Double {
            settled = false

            val wobble = Math.sin(2.0 * PI * DRIFT_SLOW_HZ * time + phaseSlow) +
                DRIFT_FAST_WEIGHT * Math.sin(2.0 * PI * DRIFT_FAST_HZ * time + phaseFast)
            val request = raw + driftAmplitudeDeg * wobble / (1.0 + DRIFT_FAST_WEIGHT)

            var r = ref ?: current
            val chase = 1.0 - Math.exp(-REFERENCE_GAIN_PER_SEC * dt)
            r += (if (wraps) Mth.wrapDegrees(request - r) else request - r) * chase
            if (wraps) r = Mth.wrapDegrees(r)
            ref = r

            val error = if (wraps) Mth.wrapDegrees(r - current) else r - current
            if (Math.abs(error) <= EPSILON && Math.abs(vel) <= SETTLE_SPEED) {
                settled = true
                reset()
                return 0.0
            }

            val desired = Mth.clamp(error * STEERING_GAIN_PER_SEC, -maxRate, maxRate)
            val dvMax = MAX_ACCEL_DEG_PER_SEC_SQ * dt
            vel += (desired - vel).coerceIn(-dvMax, dvMax)
            return vel * dt
        }

        fun reset() {
            ref = null
            vel = 0.0
        }
    }

    private fun lookFromTo(from: Vec3, to: Vec3, maxStepDegrees: Double) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val horizontal = sqrt(dx * dx + dz * dz)
        val yaw = Mth.wrapDegrees(Math.toDegrees(Mth.atan2(dz, dx)).toFloat() - 90.0f)
        val pitch = Mth.wrapDegrees(-Math.toDegrees(Mth.atan2(dy, horizontal)).toFloat())
        rotateTo(yaw, pitch, maxStepDegrees)
    }
}
