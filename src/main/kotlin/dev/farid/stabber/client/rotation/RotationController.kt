package dev.farid.stabber.client.rotation

import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * Rotates the player by feeding synthetic mouse deltas into [net.minecraft.client.MouseHandler],
 * so rotation still flows through vanilla's own turn path (sensitivity, invert options, tutorial
 * hook, vehicle passenger turning).
 *
 * Control is closed-loop: every frame the remaining error is recomputed from the player's live
 * rotation, so smooth-camera smoothing and rounding simply settle over the following frames.
 */
object RotationController {

    /** Maximum rotation applied per frame when the caller does not specify one. */
    const val DEFAULT_MAX_STEP: Double = 30.0

    /** Below this many degrees an axis counts as reached. */
    private const val EPSILON: Double = 0.01

    /** Synthetic mouse delta, in the same units as MouseHandler's accumulated movement. */
    class Step(val dx: Double, val dy: Double)

    var targetYaw: Float? = null
        private set

    var targetPitch: Float? = null
        private set

    private var maxStep: Double = DEFAULT_MAX_STEP

    val isRotating: Boolean
        get() = targetYaw != null || targetPitch != null

    /**
     * Rotates toward [yaw] and/or [pitch]; a null component leaves that axis under user control.
     * [maxStepDegrees] caps how far each axis moves per frame.
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

    /** Reaches the target in a single frame. */
    fun snapTo(yaw: Float?, pitch: Float?) {
        rotateTo(yaw, pitch, Double.MAX_VALUE)
    }

    fun lookAt(player: Entity, point: Vec3, maxStepDegrees: Double = DEFAULT_MAX_STEP) {
        val from = player.eyePosition
        val dx = point.x - from.x
        val dy = point.y - from.y
        val dz = point.z - from.z
        val horizontal = Math.sqrt(dx * dx + dz * dz)
        val yaw = Mth.wrapDegrees(Math.toDegrees(Mth.atan2(dz, dx)).toFloat() - 90.0f)
        val pitch = Mth.wrapDegrees(-Math.toDegrees(Mth.atan2(dy, horizontal)).toFloat())
        rotateTo(yaw, pitch, maxStepDegrees)
    }

    fun lookAt(player: Entity, target: Entity, atEyes: Boolean = true, maxStepDegrees: Double = DEFAULT_MAX_STEP) {
        lookAt(player, if (atEyes) target.eyePosition else target.position(), maxStepDegrees)
    }

    fun cancel() {
        targetYaw = null
        targetPitch = null
        maxStep = DEFAULT_MAX_STEP
    }

    /**
     * Returns the mouse delta that moves [player] one step toward the active target, or null when
     * idle. [degreesPerUnitX] and [degreesPerUnitY] are the signed degrees applied per unit of
     * accumulated mouse movement, so the caller owns sensitivity and the invert options.
     */
    fun consumeFrameDelta(player: Entity, degreesPerUnitX: Double, degreesPerUnitY: Double): Step? {
        val yaw = targetYaw
        val pitch = targetPitch
        if (yaw == null && pitch == null) return null

        var dx = 0.0
        if (yaw != null) {
            val error = Mth.degreesDifference(player.yRot, yaw).toDouble()
            if (Math.abs(error) <= EPSILON) {
                targetYaw = null
            } else {
                dx = toMouseDelta(error, degreesPerUnitX)
            }
        }

        var dy = 0.0
        if (pitch != null) {
            val error = (pitch - player.xRot).toDouble()
            if (Math.abs(error) <= EPSILON) {
                targetPitch = null
            } else {
                dy = toMouseDelta(error, degreesPerUnitY)
            }
        }

        if (!isRotating) {
            maxStep = DEFAULT_MAX_STEP
        }
        return Step(dx, dy)
    }

    private fun toMouseDelta(error: Double, degreesPerUnit: Double): Double {
        if (Math.abs(degreesPerUnit) < 1.0e-9) return 0.0
        val step = Mth.clamp(error, -maxStep, maxStep)
        return step / degreesPerUnit
    }
}
