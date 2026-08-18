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
    val adjacency: Map<Int, List<ManualEdge>> = buildAdjacency(edges),
) {
    companion object {
        fun buildAdjacency(edges: List<ManualEdge>): Map<Int, List<ManualEdge>> {
            val out = HashMap<Int, ArrayList<ManualEdge>>()
            for (edge in edges) {
                out.getOrPut(edge.from) { ArrayList() }.add(edge)
            }
            return out
        }
    }

    fun nearestN(worldPos: Vec3, limit: Int = 3): List<ManualNode> {
        if (limit <= 0 || nodes.isEmpty()) return emptyList()
        return nodes.values
            .sortedBy { node ->
                val centre = node.centre()
                val dx = centre.x - worldPos.x
                val dy = centre.y - worldPos.y
                val dz = centre.z - worldPos.z
                dx * dx + dy * dy + dz * dz
            }
            .take(limit)
    }
}

object ManualNodeGraph {
    private val nodes = LinkedHashMap<Int, ManualNode>()
    private val edges = ArrayList<ManualEdge>()
    private val adjacency = HashMap<Int, ArrayList<ManualEdge>>()
    private var nextId = 1
    var lastPlacedId: Int? = null
        private set

    @Synchronized
    fun snapshot(): ManualGraphSnapshot {
        return ManualGraphSnapshot(
            nodes = LinkedHashMap(nodes),
            edges = ArrayList(edges),
            lastPlacedId = lastPlacedId,
            adjacency = adjacency.mapValues { (_, outgoing) -> outgoing.toList() },
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
        rebuildAdjacency()
    }

    @Synchronized
    fun setCurrent(id: Int): Boolean {
        if (nodes[id] == null) return false
        lastPlacedId = id
        return true
    }

    @Synchronized
    fun nodeAt(pos: BlockPos): ManualNode? {
        return nodes.values.firstOrNull { it.pos == pos }
    }

    /**
     * Places a new node, or connects [lastPlacedId] to an existing node at [pos].
     * Connecting to an existing node is how loops and extra edges are made.
     */
    @Synchronized
    fun placeOrConnect(pos: BlockPos, floorY: Double, kind: PlacementKind): PlaceResult {
        val existing = nodeAt(pos)
        val previous = lastPlacedId?.let { nodes[it] }
        if (existing != null) {
            if (previous != null && previous.id != existing.id) {
                connectFrom(previous, existing)
            }
            lastPlacedId = existing.id
            return PlaceResult.Connected(existing)
        }
        val node = ManualNode(nextId++, pos.immutable(), floorY, kind)
        nodes[node.id] = node
        if (previous != null) {
            connectFrom(previous, node)
        }
        lastPlacedId = node.id
        return PlaceResult.Created(node)
    }

    sealed class PlaceResult {
        data class Created(val node: ManualNode) : PlaceResult()
        data class Connected(val node: ManualNode) : PlaceResult()
    }

    @Synchronized
    fun remove(id: Int): Boolean {
        if (nodes.remove(id) == null) return false
        edges.removeAll { it.from == id || it.to == id }
        rebuildAdjacency()
        if (lastPlacedId == id) {
            lastPlacedId = nodes.keys.lastOrNull()
        }
        return true
    }

    @Synchronized
    fun nearestN(worldPos: Vec3, limit: Int = 3): List<ManualNode> {
        return snapshot().nearestN(worldPos, limit)
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
            PlacementKind.JUMP -> addEdge(previous.id, next.id, MoveType.JUMP)
            PlacementKind.DROP -> addEdge(previous.id, next.id, MoveType.DROP)
            PlacementKind.NORMAL -> {
                addEdge(previous.id, next.id, MoveType.WALK)
                addEdge(next.id, previous.id, MoveType.WALK)
            }
        }
    }

    private fun addEdge(from: Int, to: Int, move: MoveType) {
        if (from == to) return
        if (edges.any { it.from == from && it.to == to }) return
        val edge = ManualEdge(from, to, move)
        edges.add(edge)
        adjacency.getOrPut(from) { ArrayList() }.add(edge)
    }

    private fun rebuildAdjacency() {
        adjacency.clear()
        for (edge in edges) {
            adjacency.getOrPut(edge.from) { ArrayList() }.add(edge)
        }
    }
}
