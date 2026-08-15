package dev.farid.stabber.client.path

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

object HybridPathAssembler {
    private const val NEAR_XZ = 0.75

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

        val startManual = nearest(snapshot, playerFeet) ?: return PathResult.EMPTY
        val goalCentre = Vec3(goalHint.x + 0.5, goalHint.y.toDouble(), goalHint.z + 0.5)
        val endManual = nearest(snapshot, goalCentre) ?: return PathResult.EMPTY

        val steps = GraphPathfinder.find(snapshot, startManual.id, endManual.id) ?: return PathResult.EMPTY
        val graphNodes = GraphPathfinder.toPathNodes(steps)
        if (graphNodes.isEmpty()) return PathResult.EMPTY

        val combined = ArrayList<PathNode>()

        val startCentre = startManual.centre()
        val farFromStart = hypot(playerFeet.x - startCentre.x, playerFeet.z - startCentre.z) > NEAR_XZ
        if (farFromStart) {
            val head = AStarPathfinder.find(world, playerPos, startManual.pos, cancelled) ?: return null
            if (cancelled?.get() == true) return null
            if (head.complete && head.raw.isNotEmpty()) {
                combined.addAll(dropLastIfSame(head.raw, graphNodes.first()))
            }
        }

        combined.addAll(graphNodes)

        val tail = AStarPathfinder.find(world, endManual.pos, goalHint, cancelled) ?: return null
        if (cancelled?.get() == true) return null
        if (!tail.complete || tail.raw.isEmpty()) {
            return PathResult.EMPTY
        }
        combined.addAll(dropFirstIfSame(tail.raw, combined.lastOrNull()))

        val simplified = PathSimplifier.simplify(combined)
        return PathResult(combined, simplified, complete = true, goal = goalHint.immutable())
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
