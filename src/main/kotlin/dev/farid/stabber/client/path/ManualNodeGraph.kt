package dev.farid.stabber.client.path

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot

enum class PlacementKind {
    NORMAL,
    JUMP,
    DROP,
}

data class ManualNode(
    val id: Int,
    val pos: BlockPos,
    val floorY: Double,
    val kind: PlacementKind,
) {
    fun centre(): Vec3 = StandingPositions.nodeCentre(pos, floorY)
}

data class ManualEdge(
    val from: Int,
    val to: Int,
    val move: MoveType,
)

data class ManualGraphSnapshot(
    val nodes: Map<Int, ManualNode>,
    val edges: List<ManualEdge>,
    val lastPlacedId: Int?,
)

object ManualNodeGraph {
    private val nodes = LinkedHashMap<Int, ManualNode>()
    private val edges = ArrayList<ManualEdge>()
    private var nextId = 1
    var lastPlacedId: Int? = null
        private set

    @Synchronized
    fun snapshot(): ManualGraphSnapshot {
        return ManualGraphSnapshot(
            nodes = LinkedHashMap(nodes),
            edges = ArrayList(edges),
            lastPlacedId = lastPlacedId,
        )
    }

    @Synchronized
    fun isEmpty(): Boolean = nodes.isEmpty()

    @Synchronized
    fun node(id: Int): ManualNode? = nodes[id]

    @Synchronized
    fun replaceAll(loaded: ManualGraphSnapshot) {
        nodes.clear()
        nodes.putAll(loaded.nodes)
        edges.clear()
        edges.addAll(loaded.edges)
        lastPlacedId = loaded.lastPlacedId
        nextId = (nodes.keys.maxOrNull() ?: 0) + 1
    }

    @Synchronized
    fun place(pos: BlockPos, floorY: Double, kind: PlacementKind): ManualNode? {
        if (nodes.values.any { it.pos == pos }) return null
        val node = ManualNode(nextId++, pos.immutable(), floorY, kind)
        val previous = lastPlacedId?.let { nodes[it] }
        nodes[node.id] = node
        if (previous != null) {
            connectFrom(previous, node)
        }
        lastPlacedId = node.id
        return node
    }

    @Synchronized
    fun remove(id: Int): Boolean {
        if (nodes.remove(id) == null) return false
        edges.removeAll { it.from == id || it.to == id }
        if (lastPlacedId == id) {
            lastPlacedId = nodes.keys.lastOrNull()
        }
        return true
    }

    @Synchronized
    fun nearestTo(worldPos: Vec3): ManualNode? {
        var best: ManualNode? = null
        var bestDist = Double.POSITIVE_INFINITY
        for (node in nodes.values) {
            val centre = node.centre()
            val dist = hypot(centre.x - worldPos.x, hypot(centre.y - worldPos.y, centre.z - worldPos.z))
            if (dist < bestDist) {
                bestDist = dist
                best = node
            }
        }
        return best
    }

    @Synchronized
    fun nearestTo(pos: BlockPos): ManualNode? {
        return nearestTo(Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5))
    }

    private fun connectFrom(previous: ManualNode, next: ManualNode) {
        when (previous.kind) {
            PlacementKind.JUMP -> edges.add(ManualEdge(previous.id, next.id, MoveType.JUMP))
            PlacementKind.DROP -> edges.add(ManualEdge(previous.id, next.id, MoveType.DROP))
            PlacementKind.NORMAL -> {
                edges.add(ManualEdge(previous.id, next.id, MoveType.WALK))
                edges.add(ManualEdge(next.id, previous.id, MoveType.WALK))
            }
        }
    }
}
