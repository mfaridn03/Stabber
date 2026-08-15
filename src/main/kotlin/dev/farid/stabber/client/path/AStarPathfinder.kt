package dev.farid.stabber.client.path

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

object AStarPathfinder {
    const val MAX_EXPANSIONS = 2000
    const val MAX_RADIUS = 64
    const val MAX_GAP = 3.0
    const val JUMP_EPSILON = 0.05
    const val GAP_COST_PER_BLOCK = 0.25
    const val VERTICAL_COST_WEIGHT = 1.05
    internal const val MAX_DROP_SCAN = 64
    private const val GOAL_SEARCH_RADIUS = 2
    private const val SWEEPS_PER_NODE = 80
    private const val EPS = 1.0e-6

    private val OFFSETS: IntArray = buildList {
        for (dx in -3..3) {
            for (dz in -3..3) {
                if (dx == 0 && dz == 0) continue
                add(dx)
                add(dz)
            }
        }
    }.toIntArray()

    fun find(level: Level, startHint: BlockPos, goalHint: BlockPos): PathResult {
        return find(LevelPathingWorld(level), startHint, goalHint) ?: PathResult.EMPTY
    }

    fun find(
        level: PathingWorld,
        startHint: BlockPos,
        goalHint: BlockPos,
        cancelled: AtomicBoolean? = null,
    ): PathResult? {
        if (cancelled?.get() == true) return null
        val start = resolveStanding(level, startHint) ?: return PathResult.EMPTY
        val goal = resolveStanding(level, goalHint) ?: return PathResult.EMPTY
        val search = Search(level, start, goal, cancelled)
        return search.run()
    }

    fun resolveStanding(level: Level, hint: BlockPos): PathNode? {
        return resolveStanding(LevelPathingWorld(level), hint)
    }

    fun resolveStanding(level: PathingWorld, hint: BlockPos): PathNode? {
        standingNode(level, hint)?.let { return it }
        var scan = hint.below()
        repeat(8) {
            standingNode(level, scan)?.let { return it }
            scan = scan.below()
        }
        var best: PathNode? = null
        var bestDist = Double.POSITIVE_INFINITY
        for (dx in -GOAL_SEARCH_RADIUS..GOAL_SEARCH_RADIUS) {
            for (dy in -GOAL_SEARCH_RADIUS..GOAL_SEARCH_RADIUS) {
                for (dz in -GOAL_SEARCH_RADIUS..GOAL_SEARCH_RADIUS) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    val pos = hint.offset(dx, dy, dz)
                    val node = standingNode(level, pos) ?: continue
                    val dist = hypot(dx.toDouble(), hypot(dy.toDouble(), dz.toDouble()))
                    if (dist < bestDist) {
                        bestDist = dist
                        best = node
                    }
                }
            }
        }
        return best
    }

    private fun standingNode(level: PathingWorld, pos: BlockPos): PathNode? {
        val floorY = StandingPositions.floorHeight(level, pos) ?: return null
        if (!StandingPositions.hasClearance(level, pos, floorY)) return null
        return PathNode(pos.immutable(), floorY)
    }

    private class Standing(val pos: BlockPos, val floorY: Double) {
        val packed: Long = pos.asLong()

        fun toNode(): PathNode = PathNode(pos, floorY)
    }

    private class Search(
        private val level: PathingWorld,
        private val start: PathNode,
        private val goal: PathNode,
        private val cancelled: AtomicBoolean?,
    ) {
        private val standCache = Long2ObjectOpenHashMap<Standing>()
        private val standKnown = LongOpenHashSet()
        private val bestG = Long2DoubleOpenHashMap().apply { defaultReturnValue(Double.POSITIVE_INFINITY) }
        private val closed = LongOpenHashSet()
        private val open = PriorityQueue<PathNode>(compareBy<PathNode> { it.f }.thenBy { it.h })

        fun run(): PathResult? {
            start.h = heuristic(start)
            start.g = 0.0
            open.add(start)
            bestG.put(start.packed, 0.0)

            var bestPartial = start
            var expansions = 0

            while (open.isNotEmpty() && expansions < MAX_EXPANSIONS) {
                if (cancelled?.get() == true) return null
                val current = open.poll()
                if (current.g > bestG.get(current.packed) + EPS) continue
                if (!closed.add(current.packed)) continue
                expansions++

                if (current.h < bestPartial.h - EPS ||
                    (abs(current.h - bestPartial.h) <= EPS && current.g < bestPartial.g)
                ) {
                    bestPartial = current
                }

                if (current.packed == goal.packed) {
                    return PathResult(reconstruct(current), complete = true, goal = goal.pos)
                }

                expand(current)
            }

            if (cancelled?.get() == true) return null
            return PathResult(reconstruct(bestPartial), complete = false, goal = goal.pos)
        }

        private fun expand(current: PathNode) {
            var sweeps = 0
            var i = 0
            while (i < OFFSETS.size) {
                val dx = OFFSETS[i]
                val dz = OFFSETS[i + 1]
                i += 2
                if (shouldPrune(current, dx, dz)) continue
                val candidates = verticalCandidates(current.pos.x + dx, current.pos.y, current.pos.z + dz)
                for (standing in candidates) {
                    if (closed.contains(standing.packed)) continue
                    if (chebyshev(start.pos, standing.pos) > MAX_RADIUS) continue
                    if (sweeps >= SWEEPS_PER_NODE) return
                    sweeps++
                    val candidate = standing.toNode()
                    val move = classify(current, candidate) ?: continue
                    val cost = current.g + edgeCost(current, candidate, move)
                    if (cost + EPS >= bestG.get(candidate.packed)) continue
                    candidate.parent = current
                    candidate.g = cost
                    candidate.h = heuristic(candidate)
                    candidate.incoming = move
                    bestG.put(candidate.packed, cost)
                    open.add(candidate)
                }
            }
        }

        private fun shouldPrune(from: PathNode, dx: Int, dz: Int): Boolean {
            val g = gcd(abs(dx), abs(dz))
            if (g <= 1) return false
            val sx = dx / g
            val sz = dz / g
            val mid = cachedStanding(from.pos.x + sx, from.pos.y, from.pos.z + sz)
                ?: cachedStanding(from.pos.x + sx, from.pos.y + 1, from.pos.z + sz)
                ?: cachedStanding(from.pos.x + sx, from.pos.y - 1, from.pos.z + sz)
            return mid != null
        }

        private fun verticalCandidates(x: Int, baseY: Int, z: Int): List<Standing> {
            val out = ArrayList<Standing>(4)
            fun add(y: Int) {
                val node = cachedStanding(x, y, z) ?: return
                if (out.none { it.packed == node.packed }) out.add(node)
            }
            add(baseY + 1)
            add(baseY)
            add(baseY - 1)
            var y = baseY - 2
            val minY = max(level.minY + 1, baseY - MAX_DROP_SCAN)
            while (y >= minY) {
                val pos = BlockPos(x, y, z)
                if (!level.isLoaded(pos)) break
                val node = cachedStanding(x, y, z)
                if (node != null) {
                    if (out.none { it.packed == node.packed }) out.add(node)
                    break
                }
                y--
            }
            return out
        }

        private fun classify(from: PathNode, to: PathNode): MoveType? {
            val fromCentre = StandingPositions.nodeCentre(from.pos, from.floorY)
            val toCentre = StandingPositions.nodeCentre(to.pos, to.floorY)

            // Tried first, and independently of the endpoint delta: a staircase climbs a full block per
            // tread but is walkable, because each individual rise stays inside step height.
            if (StandingPositions.sweepClearWalking(level, fromCentre, toCentre, from.floorY, to.floorY)) {
                return MoveType.WALK
            }

            val dy = to.floorY - from.floorY
            if (dy > StandingPositions.JUMP_HEIGHT + EPS) return null

            if (dy > StandingPositions.STEP_HEIGHT) {
                val apex = max(from.floorY, to.floorY)
                return if (StandingPositions.sweepClearElevating(
                        level,
                        fromCentre,
                        toCentre,
                        from.floorY,
                        to.floorY,
                        apex,
                    )
                ) {
                    MoveType.JUMP
                } else {
                    null
                }
            }

            if (dy < -StandingPositions.STEP_HEIGHT) {
                return if (StandingPositions.sweepClearElevating(
                        level,
                        fromCentre,
                        toCentre,
                        from.floorY,
                        to.floorY,
                        from.floorY,
                    )
                ) {
                    MoveType.DROP
                } else {
                    null
                }
            }

            val profile = StandingPositions.supportProfile(level, fromCentre, toCentre, from.floorY, to.floorY)
            if (profile.longestGap <= MAX_GAP && dy <= StandingPositions.JUMP_HEIGHT) {
                val apex = max(from.floorY, to.floorY) + 0.5
                if (StandingPositions.sweepClearElevating(
                        level,
                        fromCentre,
                        toCentre,
                        from.floorY,
                        to.floorY,
                        apex,
                    )
                ) {
                    return MoveType.JUMP
                }
            }
            return null
        }

        private fun edgeCost(from: PathNode, to: PathNode, move: MoveType): Double {
            val dx = (to.pos.x + 0.5) - (from.pos.x + 0.5)
            val dy = (to.floorY - from.floorY) * VERTICAL_COST_WEIGHT
            val dz = (to.pos.z + 0.5) - (from.pos.z + 0.5)
            val base = sqrt(dx * dx + dy * dy + dz * dz)
            return when (move) {
                MoveType.WALK, MoveType.DROP -> base
                MoveType.JUMP -> {
                    val fromCentre = StandingPositions.nodeCentre(from.pos, from.floorY)
                    val toCentre = StandingPositions.nodeCentre(to.pos, to.floorY)
                    val gap = StandingPositions.supportProfile(
                        level,
                        fromCentre,
                        toCentre,
                        from.floorY,
                        to.floorY,
                    ).longestGap
                    base + JUMP_EPSILON + GAP_COST_PER_BLOCK * gap
                }
            }
        }

        private fun heuristic(node: PathNode): Double {
            val dx = (node.pos.x + 0.5) - (goal.pos.x + 0.5)
            val dy = node.floorY - goal.floorY
            val dz = (node.pos.z + 0.5) - (goal.pos.z + 0.5)
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        private fun cachedStanding(x: Int, y: Int, z: Int): Standing? {
            val packed = BlockPos.asLong(x, y, z)
            if (standKnown.contains(packed)) return standCache.get(packed)
            standKnown.add(packed)
            val pos = BlockPos(x, y, z)
            val node = if (level.isLoaded(pos)) standingNode(level, pos) else null
            val standing = node?.let { Standing(it.pos, it.floorY) }
            if (standing != null) standCache.put(packed, standing)
            return standing
        }
    }

    private fun reconstruct(end: PathNode): List<PathNode> {
        val nodes = ArrayList<PathNode>()
        var cursor: PathNode? = end
        while (cursor != null) {
            nodes.add(PathNode(cursor.pos.immutable(), cursor.floorY, incoming = cursor.incoming))
            cursor = cursor.parent
        }
        nodes.reverse()
        return PathSimplifier.simplify(nodes)
    }

    private fun chebyshev(a: BlockPos, b: BlockPos): Int {
        return max(max(abs(a.x - b.x), abs(a.y - b.y)), abs(a.z - b.z))
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return if (x == 0) 1 else x
    }
}
