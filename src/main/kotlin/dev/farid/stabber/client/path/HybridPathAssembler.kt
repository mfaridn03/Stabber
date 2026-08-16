package dev.farid.stabber.client.path

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

object HybridPathAssembler {
    private const val NEAR_XZ = 0.75
    private const val HEAD_CANDIDATES = 3

    private val headExecutor = Executors.newFixedThreadPool(HEAD_CANDIDATES) { runnable ->
        Thread(runnable, "stabber-path-head").apply { isDaemon = true }
    }

    fun assemble(
        world: PathingWorld,
        playerPos: BlockPos,
        playerFeet: Vec3,
        goalHint: BlockPos,
        cancelled: AtomicBoolean?,
    ): PathResult? {
        if (cancelled?.get() == true) return null
        val snapshot = ManualNodeGraph.snapshot()
        if (snapshot.nodes.isEmpty()) return PathResult.EMPTY

        val startCandidates = snapshot.nearestN(playerFeet, HEAD_CANDIDATES)
        if (startCandidates.isEmpty()) return PathResult.EMPTY

        val headWin = raceHeads(world, playerPos, playerFeet, startCandidates, cancelled)
            ?: return if (cancelled?.get() == true) null else PathResult.EMPTY
        if (cancelled?.get() == true) return null

        val goalCentre = Vec3(goalHint.x + 0.5, goalHint.y.toDouble(), goalHint.z + 0.5)
        val endManual = nearest(snapshot, goalCentre) ?: return PathResult.EMPTY

        val steps = GraphPathfinder.find(snapshot, headWin.node.id, endManual.id) ?: return PathResult.EMPTY
        val graphNodes = GraphPathfinder.toPathNodes(steps)
        if (graphNodes.isEmpty()) return PathResult.EMPTY

        val combined = ArrayList<PathNode>()
        if (headWin.path.isNotEmpty()) {
            combined.addAll(dropLastIfSame(headWin.path, graphNodes.first()))
        }
        combined.addAll(graphNodes)
        val tailStartIndex = combined.size

        val tail = AStarPathfinder.find(world, endManual.pos, goalHint, cancelled) ?: return null
        if (cancelled?.get() == true) return null
        if (!tail.complete || tail.raw.isEmpty()) {
            return PathResult.EMPTY
        }
        combined.addAll(dropFirstIfSame(tail.raw, combined.lastOrNull()))

        val simplified = PathSimplifier.simplify(combined)
        return PathResult(
            combined,
            simplified,
            complete = true,
            goal = goalHint.immutable(),
            endManualPos = endManual.pos.immutable(),
            tailStartIndex = tailStartIndex,
        )
    }

    fun reassembleTail(
        world: PathingWorld,
        endManualPos: BlockPos,
        prefixRaw: List<PathNode>,
        tailStartIndex: Int,
        goalHint: BlockPos,
        cancelled: AtomicBoolean?,
    ): PathResult? {
        if (cancelled?.get() == true) return null
        val tail = AStarPathfinder.find(world, endManualPos, goalHint, cancelled) ?: return null
        if (cancelled?.get() == true) return null
        if (!tail.complete || tail.raw.isEmpty()) {
            return PathResult.EMPTY
        }
        val clamped = tailStartIndex.coerceIn(0, prefixRaw.size)
        val combined = ArrayList<PathNode>(prefixRaw.subList(0, clamped))
        combined.addAll(dropFirstIfSame(tail.raw, combined.lastOrNull()))
        val simplified = PathSimplifier.simplify(combined)
        return PathResult(
            combined,
            simplified,
            complete = true,
            goal = goalHint.immutable(),
            endManualPos = endManualPos.immutable(),
            tailStartIndex = clamped,
        )
    }

    fun anchors(playerPos: BlockPos, goalHint: BlockPos, extra: List<BlockPos>): List<BlockPos> {
        val snapshot = ManualNodeGraph.snapshot()
        val out = ArrayList<BlockPos>(snapshot.nodes.size + extra.size + 2)
        out.add(playerPos)
        out.add(goalHint)
        for (node in snapshot.nodes.values) {
            out.add(node.pos)
        }
        out.addAll(extra)
        return out
    }

    private data class HeadWin(val node: ManualNode, val path: List<PathNode>)

    private fun raceHeads(
        world: PathingWorld,
        playerPos: BlockPos,
        playerFeet: Vec3,
        candidates: List<ManualNode>,
        cancelled: AtomicBoolean?,
    ): HeadWin? {
        for (node in candidates) {
            if (near(playerFeet, node)) {
                return HeadWin(node, emptyList())
            }
        }

        val stop = AtomicBoolean(false)
        val completion = ExecutorCompletionService<HeadWin?>(headExecutor)
        val submitted = candidates.size
        for (node in candidates) {
            completion.submit {
                findHead(world, playerPos, playerFeet, node, stop)
            }
        }

        try {
            repeat(submitted) {
                while (true) {
                    if (cancelled?.get() == true) {
                        stop.set(true)
                        return null
                    }
                    val future = completion.poll(10, TimeUnit.MILLISECONDS) ?: continue
                    val win = try {
                        future.get()
                    } catch (_: Exception) {
                        null
                    }
                    if (win != null) {
                        stop.set(true)
                        return win
                    }
                    break
                }
            }
        } finally {
            stop.set(true)
        }
        return null
    }

    private fun findHead(
        world: PathingWorld,
        playerPos: BlockPos,
        playerFeet: Vec3,
        node: ManualNode,
        cancelled: AtomicBoolean,
    ): HeadWin? {
        if (cancelled.get()) return null
        if (near(playerFeet, node)) {
            return HeadWin(node, emptyList())
        }
        val head = AStarPathfinder.find(world, playerPos, node.pos, cancelled) ?: return null
        if (cancelled.get()) return null
        if (!head.complete || head.raw.isEmpty()) return null
        return HeadWin(node, head.raw)
    }

    private fun near(playerFeet: Vec3, node: ManualNode): Boolean {
        val centre = node.centre()
        return hypot(playerFeet.x - centre.x, playerFeet.z - centre.z) <= NEAR_XZ
    }

    private fun nearest(snapshot: ManualGraphSnapshot, worldPos: Vec3): ManualNode? {
        var best: ManualNode? = null
        var bestDist = Double.POSITIVE_INFINITY
        for (node in snapshot.nodes.values) {
            val centre = node.centre()
            val dist = hypot(centre.x - worldPos.x, hypot(centre.y - worldPos.y, centre.z - worldPos.z))
            if (dist < bestDist) {
                bestDist = dist
                best = node
            }
        }
        return best
    }

    private fun dropLastIfSame(head: List<PathNode>, firstGraph: PathNode): List<PathNode> {
        if (head.isEmpty()) return head
        val last = head.last()
        return if (last.pos == firstGraph.pos) head.dropLast(1) else head
    }

    private fun dropFirstIfSame(tail: List<PathNode>, lastCombined: PathNode?): List<PathNode> {
        if (tail.isEmpty() || lastCombined == null) return tail
        val first = tail.first()
        return if (first.pos == lastCombined.pos) tail.drop(1) else tail
    }
}
