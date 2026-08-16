package dev.farid.stabber.client.path

import java.util.PriorityQueue
import kotlin.math.sqrt

object GraphPathfinder {
    data class Step(val node: ManualNode, val incoming: MoveType)

    private data class SearchState(
        val id: Int,
        val g: Double,
        val f: Double,
        val parent: SearchState?,
        val move: MoveType,
    )

    fun find(snapshot: ManualGraphSnapshot, startId: Int, goalId: Int): List<Step>? {
        val start = snapshot.nodes[startId] ?: return null
        val goal = snapshot.nodes[goalId] ?: return null
        if (startId == goalId) return listOf(Step(start, MoveType.WALK))

        val adjacency = HashMap<Int, ArrayList<ManualEdge>>()
        for (edge in snapshot.edges) {
            adjacency.getOrPut(edge.from) { ArrayList() }.add(edge)
        }

        val open = PriorityQueue<SearchState>(compareBy { it.f })
        val bestG = HashMap<Int, Double>()
        open.add(SearchState(startId, 0.0, heuristic(start, goal), null, MoveType.WALK))
        bestG[startId] = 0.0

        while (open.isNotEmpty()) {
            val current = open.poll()
            if (current.id == goalId) return reconstruct(snapshot, current)
            val known = bestG[current.id] ?: continue
            if (current.g > known) continue
            val from = snapshot.nodes[current.id] ?: continue
            for (edge in adjacency[current.id].orEmpty()) {
                val to = snapshot.nodes[edge.to] ?: continue
                val cost = current.g + edgeCost(from, to)
                val previous = bestG[to.id]
                if (previous != null && cost >= previous) continue
                bestG[to.id] = cost
                open.add(SearchState(to.id, cost, cost + heuristic(to, goal), current, edge.move))
            }
        }
        return null
    }

    fun toPathNodes(steps: List<Step>): List<PathNode> {
        return steps.map { step ->
            PathNode(step.node.pos, step.node.floorY, incoming = step.incoming)
        }
    }

    private fun reconstruct(snapshot: ManualGraphSnapshot, end: SearchState): List<Step> {
        val reversed = ArrayList<Step>()
        var cursor: SearchState? = end
        while (cursor != null) {
            val node = snapshot.nodes[cursor.id]!!
            reversed.add(Step(node, cursor.move))
            cursor = cursor.parent
        }
        reversed.reverse()
        return reversed
    }

    private fun edgeCost(from: ManualNode, to: ManualNode): Double {
        val dx = (to.pos.x + 0.5) - (from.pos.x + 0.5)
        val dy = to.floorY - from.floorY
        val dz = (to.pos.z + 0.5) - (from.pos.z + 0.5)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun heuristic(from: ManualNode, to: ManualNode): Double = edgeCost(from, to)
}
