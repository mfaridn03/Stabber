package dev.farid.stabber.client.path

import dev.farid.stabber.client.StabberKeys
import dev.farid.stabber.client.target.TargetManager
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.LivingEntity

object PathfindingController {
    var path: PathResult = PathResult.EMPTY
        private set

    private var hadTarget = false

    fun tick(minecraft: Minecraft) {
        handleInput(minecraft)

        val level = minecraft.level
        val player = minecraft.player
        if (level == null || player == null) {
            clearPath()
            hadTarget = false
            return
        }

        val had = hadTarget || TargetManager.target != null
        if (!TargetManager.validate(level)) {
            if (had) {
                player.sendSystemMessage(Component.literal("Target Gone"))
            }
            hadTarget = false
            clearPath()
            return
        }

        hadTarget = true
        // Pathfinding is started explicitly; selection alone does not compute a path.
    }

    fun onDisconnect() {
        TargetManager.clear()
        hadTarget = false
        clearPath()
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
                clearPath()
            }
        }
    }

    private fun clearPath() {
        path = PathResult.EMPTY
    }
}
