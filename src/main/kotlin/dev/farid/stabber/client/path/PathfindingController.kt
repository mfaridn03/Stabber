package dev.farid.stabber.client.path

import dev.farid.stabber.client.StabberKeys
import dev.farid.stabber.client.target.TargetManager
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level

object PathfindingController {
    var path: PathResult = PathResult.EMPTY
        private set

    var active: Boolean = false
        private set

    private var hadTarget = false
    private var lastTargetStanding: BlockPos? = null

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
        if (!active) return

        val target = TargetManager.target ?: run {
            stopPathfinding()
            return
        }

        val standing = AStarPathfinder.resolveStanding(level, target.blockPosition())?.pos ?: return
        if (standing == lastTargetStanding) return

        if (!recomputeFromWalkBack(level, standing, target.blockPosition())) {
            stopPathfinding()
        }
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
        lastTargetStanding = AStarPathfinder.resolveStanding(level, target.blockPosition())?.pos
            ?: result.nodes.last().pos
        return true
    }

    fun onDisconnect() {
        TargetManager.clear()
        hadTarget = false
        stopPathfinding()
    }

    /**
     * When the target moves, try recomputing from the 2nd-last path node, then 3rd-last, etc.
     * Keeps the path prefix and splices on a complete suffix.
     */
    private fun recomputeFromWalkBack(level: Level, newStanding: BlockPos, goalHint: BlockPos): Boolean {
        val nodes = path.nodes
        if (nodes.size < 2) return false

        for (i in (nodes.size - 2) downTo 0) {
            val suffix = AStarPathfinder.find(level, nodes[i].pos, goalHint)
            if (!suffix.complete || suffix.nodes.isEmpty()) continue

            val prefix = nodes.subList(0, i)
            path = PathResult(
                nodes = prefix + suffix.nodes,
                complete = true,
                goal = suffix.goal,
            )
            lastTargetStanding = newStanding
            return true
        }
        return false
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
        lastTargetStanding = null
    }
}
