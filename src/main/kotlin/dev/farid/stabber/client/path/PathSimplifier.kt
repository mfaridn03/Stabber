package dev.farid.stabber.client.path

import kotlin.math.abs

/**
 * Collapses runs of collinear WALK nodes down to their endpoints.
 *
 * A* expands on a one-block grid, so a straight open corridor produces one node per block even though
 * the whole run is a single straight segment. Dropping the interior nodes keeps the traversed geometry
 * identical while cutting the node count (and therefore gizmo draw calls and follower waypoints).
 *
 * Only WALK is collapsed: JUMP and DROP nodes are individual actions a follower has to execute.
 */
object PathSimplifier {
    private const val EPS = 1.0e-6

    fun simplify(nodes: List<PathNode>): List<PathNode> {
        if (nodes.size < 3) return nodes

        val out = ArrayList<PathNode>(nodes.size)
        out.add(nodes.first())
        for (i in 1 until nodes.size - 1) {
            if (isRedundant(out.last(), nodes[i], nodes[i + 1])) continue
            out.add(nodes[i])
        }
        out.add(nodes.last())
        return if (out.size == nodes.size) nodes else out
    }

    /**
     * True when [middle] lies on the straight line from [previous] to [next] and both edges are walks,
     * so removing it leaves the same swept path.
     */
    private fun isRedundant(previous: PathNode, middle: PathNode, next: PathNode): Boolean {
        if (middle.incoming != MoveType.WALK || next.incoming != MoveType.WALK) return false

        val ax = (middle.pos.x - previous.pos.x).toDouble()
        val ay = middle.floorY - previous.floorY
        val az = (middle.pos.z - previous.pos.z).toDouble()
        val bx = (next.pos.x - middle.pos.x).toDouble()
        val by = next.floorY - middle.floorY
        val bz = (next.pos.z - middle.pos.z).toDouble()

        val crossX = ay * bz - az * by
        val crossY = az * bx - ax * bz
        val crossZ = ax * by - ay * bx
        if (abs(crossX) > EPS || abs(crossY) > EPS || abs(crossZ) > EPS) return false

        return ax * bx + ay * by + az * bz > 0.0
    }
}
