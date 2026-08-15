package dev.farid.stabber.client.path

import dev.farid.stabber.client.StabberKeys
import dev.farid.stabber.client.target.TargetManager
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.LivingEntity

object PathfindingController {
    var path: PathResult = PathResult.EMPTY
        private set

    var active: Boolean = false
        private set

    private var hadTarget = false

    fun tick(minecraft: Minecraft) {
        handleInput(minecraft)

        val level = minecraft.level
        val player = minecraft.player
        if (level == null || player == null) {
            stopPathfinding()
            hadTarget = false
            return
        }

        val had = hadTarget || TargetManager.target != null
        if (!TargetManager.validate(level)) {
            if (had) {
                player.sendSystemMessage(Component.literal("Target Gone"))
            }
            hadTarget = false
            stopPathfinding()
            return
        }

        hadTarget = true
    }

    /**
     * Starts a full pathfinding search from the local player to the selected target.
     * @return true if a complete path was found
     */
    fun startPathfinding(minecraft: Minecraft): Boolean {
        val level = minecraft.level ?: return false
        val player = minecraft.player ?: return false
        if (!TargetManager.validate(level)) return false
        val target = TargetManager.target ?: return false

        val result = AStarPathfinder.find(level, player.blockPosition(), target.blockPosition())
        if (!result.complete || result.nodes.isEmpty()) {
            stopPathfinding()
            return false
        }

        path = result
        active = true
        hadTarget = true
        return true
    }

    fun onDisconnect() {
        TargetManager.clear()
        hadTarget = false
        stopPathfinding()
    }

    private fun handleInput(minecraft: Minecraft) {
        while (StabberKeys.selectTarget.consumeClick()) {
            if (minecraft.gui.screen() != null) continue
            val picked = minecraft.crosshairPickEntity as? LivingEntity ?: continue
            if (picked === minecraft.player) continue
            val alreadySelected = TargetManager.isTarget(picked)
            TargetManager.select(picked)
            if (!alreadySelected && TargetManager.isTarget(picked)) {
                minecraft.player?.sendSystemMessage(Component.literal("Target Selected"))
                hadTarget = true
            } else if (alreadySelected) {
                hadTarget = false
                stopPathfinding()
            }
        }
    }

    private fun stopPathfinding() {
        path = PathResult.EMPTY
        active = false
    }
}
