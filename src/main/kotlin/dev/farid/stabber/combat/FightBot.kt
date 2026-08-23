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
import kotlin.math.sqrt

/**
 * The fightbot module. Select a target like for pathfinding (middle mouse on the crosshair pick),
 * then `/fight` toggles fighting it: walk at the target holding W+sprint until close enough,
 * aim per [CombatAim], and click per [HumanClicker]. Mutually exclusive with pathfinding —
 * whichever starts second stops the other.
 */
object FightBot {
    /** Horizontal distance at which forward input drops so the bot never walks inside the target. */
    const val STOP_DISTANCE_XZ: Double = 1.5

    /** Extra horizontal distance required to resume walking, so W does not chatter at the boundary. */
    const val RESUME_HYSTERESIS_XZ: Double = 0.25

    var fighting: Boolean = false
        private set

    /** True while forward input is held; carried between ticks for [RESUME_HYSTERESIS_XZ]. */
    private var walking = false

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
    }

    /**
     * Called every render frame so aiming tracks interpolated positions between ticks.
     */
    fun updateAim(minecraft: Minecraft, partialTick: Float) {
        if (!fighting) return
        CombatAim.update(minecraft, partialTick)
    }

    /**
     * Holds W (+sprint) while farther than [STOP_DISTANCE_XZ] from the target and releases inside
     * it, with [RESUME_HYSTERESIS_XZ] of hysteresis on the resume edge.
     */
    private fun updateMovement(player: LocalPlayer, target: LivingEntity) {
        val dx = target.x - player.x
        val dz = target.z - player.z
        val distXz = sqrt(dx * dx + dz * dz)
        val threshold = STOP_DISTANCE_XZ + if (walking) RESUME_HYSTERESIS_XZ else 0.0
        walking = distXz > threshold
        MovementController.apply(forward = walking, sprint = walking)
    }

    private fun reset() {
        walking = false
        MovementController.release()
        RotationController.cancel()
        CombatAim.reset()
    }
}
