package dev.farid.stabber.combat

import dev.farid.stabber.client.rotation.RotationController
import dev.farid.stabber.client.target.TargetManager
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Locks the view onto the fight target. Every render frame the aim point is derived from the
 * target's bounding box slid onto its partial-tick interpolated position, so the requested angles
 * track the same smooth motion the renderer draws, and the view is driven there as fast as the
 * GCD-safe delivery in [RotationController] allows.
 *
 * A freshly acquired target is not tracked immediately: aiming starts only after a per-acquisition
 * sampled human reaction delay, and while the view error sits inside a sampled deadzone no
 * corrections are issued at all. The deadzone tightens for a short window after each synthetic
 * click so swings still land on target.
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

    fun update(minecraft: Minecraft, partialTick: Float) {
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        if (!TargetManager.validate(level)) return
        val target = TargetManager.target ?: return

        // A new acquisition restarts the reaction window and resamples the per-fight deadzones;
        // nothing tracks until the reaction delay elapses.
        val nowNanos = System.nanoTime()
        if (target.id != acquiredTargetId) {
            acquiredTargetId = target.id
            acquiredNanos = nowNanos
            reactionDelayNanos =
                (uniform(REACTION_MIN_MS, REACTION_MAX_MS) * 1.0e6).toLong()
            holdDeadzoneDeg = uniform(HOLD_DEADZONE_MIN_DEG, HOLD_DEADZONE_MAX_DEG)
            attackDeadzoneDeg = uniform(ATTACK_DEADZONE_MIN_DEG, ATTACK_DEADZONE_MAX_DEG)
        }
        if (nowNanos - acquiredNanos < reactionDelayNanos) return

        aimAt(player, target, partialTick, nowNanos)
    }

    /** Forgets the current acquisition so the next frame samples fresh humanization constants. */
    fun reset() {
        acquiredTargetId = null
        reactionDelayNanos = 0L
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
        val centre = target.boundingBox
            .move(interp.x - target.x, interp.y - target.y, interp.z - target.z)
            .center

        val dx = centre.x - eye.x
        val dy = centre.y - eye.y
        val dz = centre.z - eye.z
        val yaw = Math.toDegrees(Mth.atan2(dz, dx)).toFloat() - 90.0f
        val pitch = (-Math.toDegrees(Mth.atan2(dy, sqrt(dx * dx + dz * dz)))).toFloat()

        // Lazy hold: while the view sits inside the tolerance band no corrections are issued at
        // all. Right after a click the band tightens so swings land on target, then widens again.
        val yawError = abs(Mth.wrapDegrees(yaw - player.yRot).toDouble())
        val pitchError = abs((pitch - player.xRot).toDouble())
        val attacking = AttackController.recentlyAttacked(nowNanos, ATTACK_WINDOW_MS)
        val deadzone = if (attacking) attackDeadzoneDeg else holdDeadzoneDeg
        if (maxOf(yawError, pitchError) <= deadzone) {
            RotationController.cancel()
            return
        }

        RotationController.snapTo(yaw, pitch)
    }

    private fun uniform(min: Double, max: Double): Double = min + Math.random() * (max - min)
}
