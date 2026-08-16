package dev.farid.stabber.client.path

import dev.farid.stabber.client.StabberKeys
import dev.farid.stabber.client.movement.PathFollower
import dev.farid.stabber.client.target.AutoTargetScanner
import dev.farid.stabber.client.target.TargetManager
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.LivingEntity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object PathfindingController {
    /** Ticks between off-path recomputes, so a search in flight is not re-submitted every tick. */
    private const val OFF_PATH_COOLDOWN = 20
    private const val MAX_AUTO_CANDIDATES = 10

    @Volatile
    var path: PathResult = PathResult.EMPTY
        private set

    var active: Boolean = false
        private set

    var autoMode: Boolean = false
        private set

    private var hadTarget = false
    private var submittedGoal: BlockPos? = null
    private var offPathCooldown = 0
    private var autoSearchInFlight = false

    private val jobId = AtomicInteger(0)
    private var inFlightCancel: AtomicBoolean? = null

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "stabber-pathfinding").apply { isDaemon = true }
    }

    fun tick(minecraft: Minecraft) {
        if (!autoMode) {
            handleInput(minecraft)
        }

        val level = minecraft.level
        val player = minecraft.player
        if (level == null || player == null) {
            if (autoMode) stopAutoPathfind() else stopPathfinding()
            hadTarget = false
            return
        }

        if (autoMode) {
            if (!isCurrentTargetValid(level)) {
                if (!autoSearchInFlight) {
                    tryAcquire(minecraft, notifyFailure = true)
                }
                return
            }
        } else {
            val had = hadTarget || TargetManager.target != null
            if (!TargetManager.validate(level)) {
                if (had) {
                    player.sendSystemMessage(Component.literal("Target Gone"))
                }
                hadTarget = false
                stopPathfinding()
                return
            }
        }

        hadTarget = true
        if (!active) return

        val target = TargetManager.target ?: run {
            stopPathfinding()
            return
        }

        if (offPathCooldown > 0) offPathCooldown--

        // Far enough off the line that the follower's strafe correction is fighting terrain rather
        // than tracking. Re-plan from where the player actually is instead of steering them back.
        if (PathFollower.offPath && offPathCooldown == 0) {
            offPathCooldown = OFF_PATH_COOLDOWN
            submit(minecraft, fullSearch = true, notifyFailure = false)
            return
        }

        val goalHint = target.blockPosition().immutable()
        if (goalHint == submittedGoal) return

        submit(minecraft, fullSearch = path.raw.size < 2 || path.endManualPos == null, notifyFailure = false)
    }

    /**
     * Toggles manual pathfinding for the currently selected target.
     */
    fun togglePathfind(minecraft: Minecraft): Boolean {
        if (active) {
            stopPathfinding()
            return false
        }
        return startPathfinding(minecraft)
    }

    /**
     * Toggles auto-targeting pathfinding. Returns the new enabled state.
     * Kept for later re-enable; not wired to `/pathfind`.
     */
    fun toggleAutoPathfind(minecraft: Minecraft): Boolean {
        if (autoMode) {
            stopAutoPathfind()
            return false
        }
        autoMode = true
        return tryAcquire(minecraft, notifyFailure = true)
    }

    fun stopAutoPathfind() {
        autoMode = false
        stopPathfinding()
    }

    /**
     * Queues a full pathfinding search from the local player to the selected target.
     * @return true if a search was submitted
     */
    fun startPathfinding(minecraft: Minecraft): Boolean {
        val level = minecraft.level ?: return false
        val player = minecraft.player ?: return false
        if (!TargetManager.validate(level)) return false
        val target = TargetManager.target ?: return false
        val playerPos = player.blockPosition()
        val goalHint = target.blockPosition()
        if (ManualNodeGraph.isEmpty()) {
            player.sendSystemMessage(Component.literal("No manual nodes"))
            return false
        }
        if (!PathfindingRegion.contains(playerPos) || !PathfindingRegion.contains(goalHint)) {
            player.sendSystemMessage(Component.literal("Outside pathfinding region"))
            return false
        }

        active = true
        hadTarget = true
        submit(minecraft, fullSearch = true, notifyFailure = true)
        return true
    }

    fun onDisconnect() {
        TargetManager.clear()
        hadTarget = false
        stopAutoPathfind()
    }

    private fun tryAcquire(minecraft: Minecraft, notifyFailure: Boolean): Boolean {
        val level = minecraft.level
        val player = minecraft.player
        if (level == null || player == null) {
            stopAutoPathfind()
            return false
        }
        if (!PathfindingRegion.contains(player.blockPosition())) {
            if (notifyFailure) {
                player.sendSystemMessage(Component.literal("Outside pathfinding region"))
            }
            stopAutoPathfind()
            return false
        }
        val candidates = AutoTargetScanner.findClosest(level, player, MAX_AUTO_CANDIDATES)
        if (ManualNodeGraph.isEmpty()) {
            if (notifyFailure) {
                player.sendSystemMessage(Component.literal("No manual nodes"))
            }
            stopAutoPathfind()
            return false
        }
        if (candidates.isEmpty()) {
            if (notifyFailure) {
                player.sendSystemMessage(Component.literal("No valid targets"))
            }
            stopAutoPathfind()
            return false
        }
        active = true
        hadTarget = true
        submitCandidateSearch(minecraft, candidates, notifyFailure)
        return true
    }

    private fun isCurrentTargetValid(level: ClientLevel): Boolean {
        if (!TargetManager.validate(level)) return false
        val target = TargetManager.target ?: return false
        return AutoTargetScanner.isValidTarget(target)
    }

    private fun submitCandidateSearch(
        minecraft: Minecraft,
        candidates: List<LivingEntity>,
        notifyFailure: Boolean,
    ) {
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        val playerPos = player.blockPosition().immutable()
        val goals = ArrayList<CandidateGoal>(candidates.size)
        for (entity in candidates) {
            val goal = entity.blockPosition().immutable()
            if (PathfindingRegion.contains(goal)) {
                goals.add(CandidateGoal(entity, goal))
            }
        }
        if (goals.isEmpty()) {
            if (notifyFailure) {
                player.sendSystemMessage(Component.literal("No valid targets"))
            }
            stopAutoPathfind()
            return
        }

        val anchors = ArrayList<BlockPos>(goals.size + 1)
        anchors.add(playerPos)
        for (goal in goals) {
            anchors.add(goal.pos)
        }

        val playerFeet = player.position()
        val world = FrozenPathingWorld.capture(level, anchors)
        val cancelled = AtomicBoolean(false)
        inFlightCancel?.set(true)
        inFlightCancel = cancelled
        val id = jobId.incrementAndGet()
        autoSearchInFlight = true
        submittedGoal = null

        executor.execute {
            var winner: CandidateGoal? = null
            var result: PathResult? = null
            for (goal in goals) {
                if (cancelled.get()) return@execute
                val found = HybridPathAssembler.assemble(world, playerPos, playerFeet, goal.pos, cancelled)
                if (found == null || cancelled.get()) return@execute
                if (found.complete && found.raw.isNotEmpty()) {
                    winner = goal
                    result = found
                    break
                }
            }
            if (cancelled.get()) return@execute

            minecraft.execute {
                if (id != jobId.get() || cancelled.get()) return@execute
                applyCandidateResult(minecraft, winner, result, notifyFailure)
            }
        }
    }

    private fun applyCandidateResult(
        minecraft: Minecraft,
        winner: CandidateGoal?,
        result: PathResult?,
        notifyFailure: Boolean,
    ) {
        autoSearchInFlight = false
        if (!autoMode) return
        val level = minecraft.level
        if (winner == null || result == null || !result.complete || result.raw.isEmpty()) {
            if (notifyFailure) {
                minecraft.player?.sendSystemMessage(Component.literal("No path found"))
            }
            stopAutoPathfind()
            return
        }
        if (level == null || !AutoTargetScanner.isValidTarget(winner.entity) || winner.entity.level() !== level) {
            tryAcquire(minecraft, notifyFailure)
            return
        }
        TargetManager.assign(winner.entity)
        path = result
        submittedGoal = winner.pos
        active = true
        hadTarget = true
    }

    private fun submit(minecraft: Minecraft, fullSearch: Boolean, notifyFailure: Boolean) {
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        val target = TargetManager.target ?: return

        val playerPos = player.blockPosition().immutable()
        // A recompute keeps the leg being walked: starting from the locked waypoint leaves the current
        // stride intact and only replans past it, instead of rewriting the path under the follower.
        val startHint = if (fullSearch) playerPos else PathFollower.lockedNode ?: playerPos
        val goalHint = target.blockPosition().immutable()
        if (!PathfindingRegion.contains(playerPos) || !PathfindingRegion.contains(goalHint)) {
            if (autoMode) {
                if (!autoSearchInFlight) {
                    tryAcquire(minecraft, notifyFailure = true)
                }
                return
            }
            if (notifyFailure) {
                player.sendSystemMessage(Component.literal("Outside pathfinding region"))
            }
            stopPathfinding()
            return
        }
        if (ManualNodeGraph.isEmpty()) {
            if (autoMode) {
                if (!autoSearchInFlight) {
                    tryAcquire(minecraft, notifyFailure = true)
                }
                return
            }
            if (notifyFailure) {
                player.sendSystemMessage(Component.literal("No manual nodes"))
            }
            stopPathfinding()
            return
        }

        val currentNodes = path.raw
        val extra = ArrayList<BlockPos>(currentNodes.size + 2)
        extra.add(startHint)
        for (node in currentNodes) {
            extra.add(node.pos)
        }
        path.endManualPos?.let { extra.add(it) }
        val anchors = HybridPathAssembler.anchors(playerPos, goalHint, extra)
        val playerFeet = player.position()

        val world = FrozenPathingWorld.capture(level, anchors)
        val cancelled = AtomicBoolean(false)
        inFlightCancel?.set(true)
        inFlightCancel = cancelled
        val id = jobId.incrementAndGet()
        submittedGoal = goalHint

        val tailOnly = !fullSearch && path.endManualPos != null
        val prefixRaw = path.raw
        val endManualPos = path.endManualPos
        val tailStartIndex = path.tailStartIndex

        executor.execute {
            val result = if (tailOnly && endManualPos != null) {
                HybridPathAssembler.reassembleTail(
                    world,
                    endManualPos,
                    prefixRaw,
                    tailStartIndex,
                    goalHint,
                    cancelled,
                )
            } else {
                HybridPathAssembler.assemble(world, playerPos, playerFeet, goalHint, cancelled)
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
        if (!result.complete || result.raw.isEmpty()) {
            if (autoMode) {
                tryAcquire(minecraft, notifyFailure = true)
                return
            }
            if (notifyFailure) {
                val msg = if (ManualNodeGraph.isEmpty()) "No manual nodes" else "No path found"
                minecraft.player?.sendSystemMessage(Component.literal(msg))
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
                startPathfinding(minecraft)
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
        offPathCooldown = 0
        autoSearchInFlight = false
    }

    private data class CandidateGoal(val entity: LivingEntity, val pos: BlockPos)
}
