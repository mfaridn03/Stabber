package dev.farid.stabber.client.path

import dev.farid.stabber.client.StabberKeys
import dev.farid.stabber.client.target.TargetManager
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.LivingEntity

object PathfindingController {
    const val REPATH_INTERVAL = 10

    var path: PathResult = PathResult.EMPTY
        private set

    private var ticksUntilRepath = 0
    private var lastGoal: BlockPos? = null
    private var lastStart: BlockPos? = null

    fun tick(minecraft: Minecraft) {
        handleInput(minecraft)

        val level = minecraft.level
        val player = minecraft.player
        if (level == null || player == null || !TargetManager.validate(level)) {
            clearPath()
            return
        }

        val target = TargetManager.target ?: run {
            clearPath()
            return
        }

        val start = player.blockPosition()
        val goal = target.blockPosition()
        val moved = start != lastStart || goal != lastGoal
        if (moved || ticksUntilRepath <= 0) {
            path = AStarPathfinder.find(level, start, goal)
            lastStart = start
            lastGoal = goal
            ticksUntilRepath = REPATH_INTERVAL
        } else {
            ticksUntilRepath--
        }
    }

    fun onDisconnect() {
        TargetManager.clear()
        clearPath()
    }

    private fun handleInput(minecraft: Minecraft) {
        while (StabberKeys.selectTarget.consumeClick()) {
            if (minecraft.screen != null) continue
            val picked = minecraft.crosshairPickEntity as? LivingEntity ?: continue
            if (picked === minecraft.player) continue
            TargetManager.select(picked)
            ticksUntilRepath = 0
        }
    }

    private fun clearPath() {
        path = PathResult.EMPTY
        lastGoal = null
        lastStart = null
        ticksUntilRepath = 0
    }
}
