package dev.farid.stabber.client.path

import dev.farid.stabber.client.StabberKeys
import dev.farid.stabber.client.target.TargetManager
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.LivingEntity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object PathfindingController {
    @Volatile
    var path: PathResult = PathResult.EMPTY
        private set

    var active: Boolean = false
        private set

    private var hadTarget = false
    private var submittedGoal: BlockPos? = null

    private val jobId = AtomicInteger(0)
    private var inFlightCancel: AtomicBoolean? = null

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "stabber-pathfinding").apply { isDaemon = true }
    }

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

        val goalHint = target.blockPosition().immutable()
        if (goalHint == submittedGoal) return

        submit(minecraft, fullSearch = path.nodes.size < 2, notifyFailure = false)
    }

    /**
     * Queues a full pathfinding search from the local player to the selected target.
     * @return true if a search was submitted
     */
    fun startPathfinding(minecraft: Minecraft): Boolean {
        val level = minecraft.level ?: return false
        if (minecraft.player == null) return false
        if (!TargetManager.validate(level)) return false
        if (TargetManager.target == null) return false

        active = true
        hadTarget = true
        submit(minecraft, fullSearch = true, notifyFailure = true)
        return true
    }

    fun onDisconnect() {
        TargetManager.clear()
        hadTarget = false
        stopPathfinding()
    }

    private fun submit(minecraft: Minecraft, fullSearch: Boolean, notifyFailure: Boolean) {
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        val target = TargetManager.target ?: return

        val startHint = player.blockPosition().immutable()
        val goalHint = target.blockPosition().immutable()
        val currentNodes = path.nodes
        val anchors = ArrayList<BlockPos>(currentNodes.size + 2)
        anchors.add(startHint)
        anchors.add(goalHint)
        for (node in currentNodes) {
            anchors.add(node.pos)
        }

        val world = FrozenPathingWorld.capture(level, anchors)
        val cancelled = AtomicBoolean(false)
        inFlightCancel?.set(true)
        inFlightCancel = cancelled
        val id = jobId.incrementAndGet()
        submittedGoal = goalHint

        executor.execute {
            val result = if (fullSearch || currentNodes.size < 2) {
                AStarPathfinder.find(world, startHint, goalHint, cancelled)
            } else {
                PathRetarget.recompute(world, currentNodes, startHint, goalHint, cancelled)
            }
            if (result == null || cancelled.get()) return@execute

            minecraft.execute {
                if (id != jobId.get() || cancelled.get()) return@execute
                applyResult(minecraft, result, notifyFailure)
            }
        }
    }

    private fun applyResult(minecraft: Minecraft, result: PathResult, notifyFailure: Boolean) {
        if (!active) return
        if (!result.complete || result.nodes.isEmpty()) {
            if (notifyFailure) {
                minecraft.player?.sendSystemMessage(Component.literal("No path found"))
            }
            stopPathfinding()
            return
        }

        path = result
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
        inFlightCancel?.set(true)
        inFlightCancel = null
        jobId.incrementAndGet()
        path = PathResult.EMPTY
        active = false
        submittedGoal = null
    }
}
