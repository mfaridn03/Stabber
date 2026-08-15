package dev.farid.stabber.client.path

/**
 * Greedy string-pulling (line-of-sight shortcuts) over an A* path.
 *
 * Collinear [PathSimplifier] only drops nodes that already lie on a straight edge. Open terrain still
 * leaves stair-step zigzags from grid expansion. This pass repeatedly replaces a walk subpath with the
 * furthest single walk edge that [StandingPositions.sweepClearWalking] accepts.
 *
 * JUMP and DROP nodes are hard breakpoints: a shortcut may never skip past a non-WALK incoming move.
 */
object PathStringPuller {
    fun pull(level: PathingWorld, nodes: List<PathNode>): List<PathNode> {
        val simplified = PathSimplifier.simplify(nodes)
        if (simplified.size < 3) return simplified

        val out = ArrayList<PathNode>(simplified.size)
        out.add(simplified.first())

        var i = 0
        while (i < simplified.size - 1) {
            var best = i + 1
            var j = i + 2
            while (j < simplified.size) {
                if (!walkSegment(simplified, i, j)) break
                if (canWalk(level, simplified[i], simplified[j])) {
                    best = j
                }
                j++
            }

            val target = simplified[best]
            val incoming = if (best == i + 1) target.incoming else MoveType.WALK
            out.add(PathNode(target.pos.immutable(), target.floorY, incoming = incoming))
            i = best
        }

        return if (out.size == simplified.size) simplified else out
    }

    /** True when every hop from [from] exclusive through [to] inclusive is a walk. */
    private fun walkSegment(nodes: List<PathNode>, from: Int, to: Int): Boolean {
        for (k in (from + 1)..to) {
            if (nodes[k].incoming != MoveType.WALK) return false
        }
        return true
    }

    private fun canWalk(level: PathingWorld, from: PathNode, to: PathNode): Boolean {
        val fromCentre = StandingPositions.nodeCentre(from.pos, from.floorY)
        val toCentre = StandingPositions.nodeCentre(to.pos, to.floorY)
        return StandingPositions.sweepClearWalking(level, fromCentre, toCentre, from.floorY, to.floorY)
    }
}
