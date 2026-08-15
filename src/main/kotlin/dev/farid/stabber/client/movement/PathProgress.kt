package dev.farid.stabber.client.movement

import dev.farid.stabber.client.path.MoveType
import dev.farid.stabber.client.path.PathNode
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Where the player sits on the path polyline, and the aim point derived from it.
 *
 * The follower's waypoint used to be picked by distance thresholds, which are not monotonic: drifting
 * sideways lets an earlier waypoint re-cross the threshold and the view whips back to it. Progress here
 * is an arc position that can only move forward, so the aim point cannot oscillate.
 */
object PathProgress {
    private const val EPS = 1.0e-6
    /** Must be on the landing slab, not merely past it in XZ, to leave a jump or drop. */
    private const val LANDING_REACH_Y = 0.6

    /**
     * @param index segment from `nodes[index]` to `nodes[index + 1]`
     * @param t position along that segment, clamped to 0..1
     * @param crossTrack signed perpendicular offset from the segment line; positive is right of travel
     * @param dirX unit segment direction, X component
     * @param dirZ unit segment direction, Z component
     */
    class Fix(
        val index: Int,
        val t: Double,
        val crossTrack: Double,
        val dirX: Double,
        val dirZ: Double,
    )

    /**
     * Advances from [fromIndex] past every segment the player has already left, then measures their
     * offset from the segment they are on. Never returns a segment earlier than [fromIndex].
     *
     * A walk segment counts as left once the player's XZ projection passes its end node, or once they
     * are within [reachRadius] of that node, which covers arriving off-axis at a tight corner.
     *
     * Jump and drop landings also require the player's feet to be near the landing height. XZ travel
     * during a sprint-jump otherwise marks several following treads done while still in the air.
     *
     * Null when [nodes] has no segment to sit on.
     */
    fun project(
        nodes: List<PathNode>,
        x: Double,
        y: Double,
        z: Double,
        fromIndex: Int,
        reachRadius: Double,
    ): Fix? {
        val lastSegment = nodes.size - 2
        if (lastSegment < 0) return null

        var index = fromIndex.coerceIn(0, lastSegment)
        while (index < lastSegment) {
            if (!hasLeft(nodes, index, x, y, z, reachRadius)) break
            index++
        }

        val ax = centreX(nodes[index])
        val az = centreZ(nodes[index])
        val dx = centreX(nodes[index + 1]) - ax
        val dz = centreZ(nodes[index + 1]) - az
        val length = hypot(dx, dz)
        if (length <= EPS) return Fix(index, 1.0, 0.0, 0.0, 0.0)

        val ux = dx / length
        val uz = dz / length
        val t = (((x - ax) * ux + (z - az) * uz) / length).coerceIn(0.0, 1.0)
        // Right of a heading (ux, uz) is (-uz, ux): facing south (0, 1) puts right at west (-1, 0).
        val crossTrack = (x - ax) * -uz + (z - az) * ux
        return Fix(index, t, crossTrack, ux, uz)
    }

    /** Segment whose line the player is closest to, for re-seeding progress onto a freshly published path. */
    fun nearestSegment(nodes: List<PathNode>, x: Double, z: Double): Int {
        var best = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (index in 0..nodes.size - 2) {
            val ax = centreX(nodes[index])
            val az = centreZ(nodes[index])
            val dx = centreX(nodes[index + 1]) - ax
            val dz = centreZ(nodes[index + 1]) - az
            val lengthSq = dx * dx + dz * dz
            val t = if (lengthSq <= EPS) 0.0 else (((x - ax) * dx + (z - az) * dz) / lengthSq).coerceIn(0.0, 1.0)
            val distance = hypot(x - (ax + dx * t), z - (az + dz * t))
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    /**
     * Point [lookahead] blocks further along the polyline than [fix] — the head's target.
     *
     * Sliding a point along the path rather than switching between waypoints is what keeps the turn
     * into a corner continuous. The walk stops early at a node reached by a jump or a drop: looking
     * past the landing would turn the head away mid-air.
     */
    fun carrot(nodes: List<PathNode>, fix: Fix, lookahead: Double): Vec3 {
        var index = fix.index
        var t = fix.t
        var remaining = lookahead

        while (true) {
            val length = segmentLength(nodes, index)
            val available = length * (1.0 - t)
            if (length > EPS && remaining <= available) {
                return pointAt(nodes, index, min(1.0, t + remaining / length))
            }
            remaining -= available
            if (index == nodes.size - 2) return pointAt(nodes, index, 1.0)
            if (nodes[index + 1].incoming != MoveType.WALK) return pointAt(nodes, index, 1.0)
            index++
            t = 0.0
        }
    }

    /** Path length still ahead of [fix]; used to stop steering once there is nothing left to aim at. */
    fun remainingLength(nodes: List<PathNode>, fix: Fix): Double {
        var total = segmentLength(nodes, fix.index) * (1.0 - fix.t)
        for (index in (fix.index + 1)..(nodes.size - 2)) {
            total += segmentLength(nodes, index)
        }
        return total
    }

    private fun hasLeft(
        nodes: List<PathNode>,
        index: Int,
        x: Double,
        y: Double,
        z: Double,
        reachRadius: Double,
    ): Boolean {
        val end = nodes[index + 1]
        val pastEnd = rawT(nodes, index, x, z) >= 1.0
        val xzClose = distanceTo(end, x, z) <= reachRadius
        if (!pastEnd && !xzClose) return false
        if (end.incoming == MoveType.WALK) return true
        return abs(y - end.floorY) <= LANDING_REACH_Y
    }

    /** Unclamped projection parameter; at or above 1.0 the player is past the segment's end node. */
    private fun rawT(nodes: List<PathNode>, index: Int, x: Double, z: Double): Double {
        val ax = centreX(nodes[index])
        val az = centreZ(nodes[index])
        val dx = centreX(nodes[index + 1]) - ax
        val dz = centreZ(nodes[index + 1]) - az
        val lengthSq = dx * dx + dz * dz
        if (lengthSq <= EPS) return 1.0
        return ((x - ax) * dx + (z - az) * dz) / lengthSq
    }

    private fun pointAt(nodes: List<PathNode>, index: Int, t: Double): Vec3 {
        val from = nodes[index]
        val to = nodes[index + 1]
        return Vec3(
            centreX(from) + (centreX(to) - centreX(from)) * t,
            from.floorY + (to.floorY - from.floorY) * t,
            centreZ(from) + (centreZ(to) - centreZ(from)) * t,
        )
    }

    private fun segmentLength(nodes: List<PathNode>, index: Int): Double {
        return hypot(
            centreX(nodes[index + 1]) - centreX(nodes[index]),
            centreZ(nodes[index + 1]) - centreZ(nodes[index]),
        )
    }

    private fun distanceTo(node: PathNode, x: Double, z: Double): Double {
        return hypot(x - centreX(node), z - centreZ(node))
    }

    private fun centreX(node: PathNode): Double = node.pos.x + 0.5

    private fun centreZ(node: PathNode): Double = node.pos.z + 0.5
}
