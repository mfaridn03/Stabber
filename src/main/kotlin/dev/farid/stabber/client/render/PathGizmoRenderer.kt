package dev.farid.stabber.client.render

import dev.farid.stabber.client.path.MoveType
import dev.farid.stabber.client.path.PathNode
import dev.farid.stabber.client.path.PathResult
import dev.farid.stabber.client.path.StandingPositions
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.util.ARGB
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB

object PathGizmoRenderer {
    private const val LINE_WIDTH = 3f
    private const val LIFT = 0.12
    private const val NODE_HALF = 0.18
    private const val NODE_HEIGHT = 0.22

    private const val WALK = 0xFF44CCFF.toInt()
    private const val JUMP = 0xFFFFCC44.toInt()
    private const val DROP = 0xFFCC66FF.toInt()
    private const val INCOMPLETE = 0xFFFF5555.toInt()
    private val GOAL_OK = GizmoStyle.strokeAndFill(0xFF00FF88.toInt(), 2f, 0x3300FF88)
    private val GOAL_BAD = GizmoStyle.strokeAndFill(0xFFFF5555.toInt(), 2f, 0x33FF5555)

    fun render(path: PathResult, target: LivingEntity?) {
        if (path.nodes.isEmpty() && target == null) return

        val nodes = path.nodes
        for (i in nodes.indices) {
            val node = nodes[i]
            val outgoing = outgoingMove(nodes, i)
            val color = if (path.complete) colorFor(outgoing) else INCOMPLETE
            drawNode(node, color)

            if (i + 1 < nodes.size) {
                val next = nodes[i + 1]
                val edgeColor = if (path.complete) colorFor(next.incoming) else INCOMPLETE
                val a = StandingPositions.nodeCentre(node.pos, node.floorY).add(0.0, LIFT, 0.0)
                val b = StandingPositions.nodeCentre(next.pos, next.floorY).add(0.0, LIFT, 0.0)
                Gizmos.line(a, b, edgeColor, LINE_WIDTH).setAlwaysOnTop()
            }
        }

        if (target != null) {
            val style = if (path.complete) GOAL_OK else GOAL_BAD
            Gizmos.cuboid(target.onPos, style).setAlwaysOnTop()
        }
    }

    /**
     * Colour a node by the move leaving it. Jump takeoff nodes (A when A->B is JUMP) use JUMP colour.
     */
    private fun outgoingMove(nodes: List<PathNode>, index: Int): MoveType {
        if (index + 1 >= nodes.size) return MoveType.WALK
        return nodes[index + 1].incoming
    }

    private fun drawNode(node: PathNode, argb: Int) {
        val centre = StandingPositions.nodeCentre(node.pos, node.floorY)
        val aabb = AABB(
            centre.x - NODE_HALF,
            centre.y,
            centre.z - NODE_HALF,
            centre.x + NODE_HALF,
            centre.y + NODE_HEIGHT,
            centre.z + NODE_HALF,
        )
        val fill = ARGB.color(
            0x88,
            ARGB.red(argb),
            ARGB.green(argb),
            ARGB.blue(argb),
        )
        Gizmos.cuboid(aabb, GizmoStyle.strokeAndFill(argb, 2f, fill)).setAlwaysOnTop()
    }

    private fun colorFor(move: MoveType): Int {
        return when (move) {
            MoveType.WALK -> WALK
            MoveType.JUMP -> JUMP
            MoveType.DROP -> DROP
        }
    }
}
