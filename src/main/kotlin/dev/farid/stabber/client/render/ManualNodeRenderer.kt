package dev.farid.stabber.client.render

import dev.farid.stabber.client.path.ManualNode
import dev.farid.stabber.client.path.ManualNodeGraph
import dev.farid.stabber.client.path.MoveType
import dev.farid.stabber.client.path.NodeEditController

import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.util.ARGB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object ManualNodeRenderer {
    private const val LINE_WIDTH = 3f
    private const val LIFT = 0.12
    private const val NODE_HALF = 0.18
    private const val NODE_HEIGHT = 0.22

    private const val GRAY = 0xFF888888.toInt()
    private const val SELECTED = 0xFFFFFFFF.toInt()
    private const val WALK = 0xFF888888.toInt()
    private const val JUMP = 0xFFFFCC44.toInt()
    private const val DROP = 0xFFCC66FF.toInt()
    private const val PREVIEW = 0xFFCCCCCC.toInt()

    fun render(playerFeet: Vec3?) {
        val snapshot = ManualNodeGraph.snapshot()
        if (snapshot.nodes.isEmpty() && playerFeet == null) return

        val highlighted = NodeEditController.highlightedId
        for (node in snapshot.nodes.values) {
            val color = if (node.id == highlighted) SELECTED else GRAY
            drawNode(node, color)
        }

        for (edge in snapshot.edges) {
            val from = snapshot.nodes[edge.from] ?: continue
            val to = snapshot.nodes[edge.to] ?: continue
            if (edge.move == MoveType.WALK && snapshot.edges.any { it.from == edge.to && it.to == edge.from && it.move == MoveType.WALK && edge.from > edge.to }) {
                continue
            }
            val a = from.centre().add(0.0, LIFT, 0.0)
            val b = to.centre().add(0.0, LIFT, 0.0)
            Gizmos.line(a, b, colorFor(edge.move), LINE_WIDTH)
        }

        if (NodeEditController.editMode && playerFeet != null) {
            val last = snapshot.lastPlacedId?.let { snapshot.nodes[it] }
            if (last != null) {
                val a = last.centre().add(0.0, LIFT, 0.0)
                val b = playerFeet.add(0.0, LIFT, 0.0)
                Gizmos.line(a, b, PREVIEW, LINE_WIDTH)
            }
        }
    }

    private fun drawNode(node: ManualNode, argb: Int) {
        val centre = node.centre()
        val aabb = AABB(
            centre.x - NODE_HALF,
            centre.y + LIFT,
            centre.z - NODE_HALF,
            centre.x + NODE_HALF,
            centre.y + LIFT + NODE_HEIGHT,
            centre.z + NODE_HALF,
        )
        val fill = ARGB.color(
            0x88,
            ARGB.red(argb),
            ARGB.green(argb),
            ARGB.blue(argb),
        )
        Gizmos.cuboid(aabb, GizmoStyle.strokeAndFill(argb, 2f, fill))
    }

    private fun colorFor(move: MoveType): Int {
        return when (move) {
            MoveType.WALK -> WALK
            MoveType.JUMP -> JUMP
            MoveType.DROP -> DROP
        }
    }
}
