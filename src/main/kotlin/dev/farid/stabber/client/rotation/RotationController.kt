package dev.farid.stabber.client.rotation

import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
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
 * Each axis moves toward its requested angle at up to [maxStep] degrees per second and counts as
 * arrived once within one mouse pixel of it, so requests recomputed against live interpolated
 * geometry every frame are tracked without lag.
 */
object RotationController {

    /** Maximum rotation applied per second when the caller does not specify one. */
    const val DEFAULT_MAX_STEP: Double = 30.0 * 60.0

    /** Floor for the settle tolerance when one mouse pixel is finer than this. */
    private const val EPSILON: Double = 0.01

    /** Synthetic mouse delta, in the same units as MouseHandler's accumulated movement. */
    class Step(val dx: Double, val dy: Double)

    var targetYaw: Float? = null
        private set

    var targetPitch: Float? = null
        private set

    /** Degrees per second. */
    private var maxStep: Double = DEFAULT_MAX_STEP

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

    /** Turns at full speed — as far as the GCD-safe delivery allows in one frame. */
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
        carryX = 0.0
        carryY = 0.0
    }

    /**
     * Returns the mouse delta that moves [player] one step toward the active target, or null when
     * idle. [degreesPerUnitX] and [degreesPerUnitY] are the signed degrees applied per unit of
     * accumulated mouse movement, so the caller owns sensitivity and the invert options; each axis
     * settles once it lands within one such unit (one mouse pixel) of its target.
     * [deltaSeconds] is the wall-clock duration of this frame; [maxStep] caps steady-state speed.
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

        // Snap to whole pixels, banking the remainder: vanilla multiplies the accumulated delta by
        // a fixed sensitivity factor, so integer deltas keep every rotation change an exact
        // multiple of that step — the grid real mouse input lands on.
        fun quantize(degrees: Double, carry: Double, degreesPerUnit: Double): Pair<Double, Double> {
            val wanted = if (abs(degreesPerUnit) < 1.0e-9) 0.0 else degrees / degreesPerUnit
            val total = wanted + carry
            val emitted = Math.rint(total)
            return emitted to total - emitted
        }

        var dx = 0.0
        if (yaw != null) {
            val error = Mth.wrapDegrees(yaw - player.yRot).toDouble()
            if (abs(error) <= maxOf(EPSILON, abs(degreesPerUnitX))) {
                targetYaw = null
                carryX = 0.0
            } else {
                val deg = error.coerceIn(-maxStep * dt, maxStep * dt)
                val (emitted, rest) = quantize(deg, carryX, degreesPerUnitX)
                carryX = rest
                dx = emitted
            }
        }

        var dy = 0.0
        if (pitch != null) {
            val error = (pitch - player.xRot).toDouble()
            if (abs(error) <= maxOf(EPSILON, abs(degreesPerUnitY))) {
                targetPitch = null
                carryY = 0.0
            } else {
                val deg = error.coerceIn(-maxStep * dt, maxStep * dt)
                val (emitted, rest) = quantize(deg, carryY, degreesPerUnitY)
                carryY = rest
                dy = emitted
            }
        }

        if (!isRotating) {
            maxStep = DEFAULT_MAX_STEP
        }
        return Step(dx, dy)
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
