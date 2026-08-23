package dev.farid.stabber.combat

import dev.farid.stabber.client.movement.MovementController
import dev.farid.stabber.client.movement.PathFollower
import dev.farid.stabber.client.path.PathfindingController
import dev.farid.stabber.client.rotation.RotationController
import dev.farid.stabber.client.target.TargetManager
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.LivingEntity
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The fightbot module. Select a target like for pathfinding (middle mouse on the crosshair pick),
 * then `/fight` toggles fighting it: walk at the target holding W until close enough,
 * aim per [CombatAim], and click per [HumanClicker]. Mutually exclusive with pathfinding —
 * whichever starts second stops the other.
 */
object FightBot {
    /** Horizontal distance at which forward input drops so the bot never walks inside the target. */
    const val STOP_DISTANCE_XZ: Double = 1.5

    /** Extra horizontal distance required to resume walking, so W does not chatter at the boundary. */
    const val RESUME_HYSTERESIS_XZ: Double = 1.0

    /** Clicking starts once the target is inside this distance band, sampled per fight. */
    const val CLICK_START_MIN_X: Double = 4.0
    const val CLICK_START_MAX_X: Double = 5.2

    /** Extra 3D distance required to stop clicking, so the gate does not flicker at the boundary. */
    const val CLICK_STOP_MARGIN_X: Double = 0.75

    /** Per-tick random walk step on the click-start distance, so it is never a constant. */
    const val CLICK_GATE_DRIFT_SIGMA_X: Double = 0.015

    /** Bounds the drifting click-start distance after enough walk accumulates. */
    const val CLICK_GATE_MIN_X: Double = CLICK_START_MIN_X - 0.3
    const val CLICK_GATE_MAX_X: Double = CLICK_START_MAX_X + 0.3

    var fighting: Boolean = false
        private set

    /** True while forward input is held; carried between ticks for [RESUME_HYSTERESIS_XZ]. */
    private var walking = false

    private val clicker = HumanClicker()

    /** True while inside the click envelope; carried between ticks for [CLICK_STOP_MARGIN_X]. */
    private var clicking = false

    /** Distance at which clicking engages this fight; drifts over time. */
    private var clickGateDistance = (CLICK_START_MIN_X + CLICK_START_MAX_X) / 2.0

    fun toggle(minecraft: Minecraft): Boolean {
        return if (fighting) {
            stop()
            false
        } else {
            start(minecraft)
        }
    }

    private fun start(minecraft: Minecraft): Boolean {
        val level = minecraft.level ?: return false
        val player = minecraft.player ?: return false
        if (!TargetManager.validate(level)) {
            player.sendSystemMessage(Component.literal("No target selected"))
            return false
        }

        PathfindingController.stop()
        PathFollower.stop()

        fighting = true
        reset()
        player.sendSystemMessage(Component.literal("Fight enabled"))
        return true
    }

    fun stop() {
        if (!fighting) return
        fighting = false
        reset()
    }

    fun tick(minecraft: Minecraft) {
        if (!fighting) return

        val level = minecraft.level
        val player = minecraft.player
        if (level == null || player == null) {
            stop()
            return
        }
        if (!TargetManager.validate(level)) {
            player.sendSystemMessage(Component.literal("Target Gone"))
            stop()
            return
        }
        // Mutual exclusion holds regardless of which entry point started pathfinding.
        if (PathfindingController.active || PathFollower.following || PathFollower.pendingStart) {
            stop()
            return
        }
        val target = TargetManager.target ?: run {
            stop()
            return
        }

        if (minecraft.gui.screen() != null || player.isDeadOrDying) {
            MovementController.release()
            return
        }

        updateMovement(player, target)
        updateClickGate(player, target)
    }

    /**
     * Called every render frame so aiming and clicking track interpolated positions between ticks.
     */
    fun updateAim(minecraft: Minecraft, partialTick: Float) {
        if (!fighting) return
        val player = minecraft.player ?: return
        if (minecraft.gui.screen() != null || player.isDeadOrDying) return

        CombatAim.update(minecraft, partialTick)

        if (clicker.tick(System.nanoTime(), clicking)) {
            AttackController.enqueue()
        }
    }

    /**
     * Holds W while farther than [STOP_DISTANCE_XZ] from the target and releases inside
     * it, with [RESUME_HYSTERESIS_XZ] of hysteresis on the resume edge.
     */
    private fun updateMovement(player: LocalPlayer, target: LivingEntity) {
        val dx = target.x - player.x
        val dz = target.z - player.z
        val distXz = sqrt(dx * dx + dz * dz)
        val threshold = STOP_DISTANCE_XZ + if (walking) RESUME_HYSTERESIS_XZ else 0.0
        walking = distXz > threshold
        MovementController.apply(forward = walking)
    }

    /**
     * Clicking starts before the target is actually in range — like a player who begins mousing as
     * they close in — but not always at the same distance: the gate is sampled per fight and slowly
     * random-walks while fighting. [CLICK_STOP_MARGIN_X] of hysteresis keeps it from flickering.
     */
    private fun updateClickGate(player: LocalPlayer, target: LivingEntity) {
        clickGateDistance = (clickGateDistance + gaussian() * CLICK_GATE_DRIFT_SIGMA_X)
            .coerceIn(CLICK_GATE_MIN_X, CLICK_GATE_MAX_X)
        val distance = player.distanceTo(target).toDouble()
        clicking = if (clicking) {
            distance <= clickGateDistance + CLICK_STOP_MARGIN_X
        } else {
            distance <= clickGateDistance
        }
    }

    private fun reset() {
        walking = false
        clicking = false
        clickGateDistance = uniform(CLICK_START_MIN_X, CLICK_START_MAX_X)
        clicker.reset(System.nanoTime())
        MovementController.release()
        RotationController.cancel()
        CombatAim.reset()
    }

    private fun gaussian(): Double {
        var u = Math.random()
        if (u < 1.0e-9) u = 1.0e-9
        return sqrt(-2.0 * ln(u)) * cos(2.0 * Math.PI * Math.random())
    }

    private fun uniform(min: Double, max: Double): Double = min + Math.random() * (max - min)
}
