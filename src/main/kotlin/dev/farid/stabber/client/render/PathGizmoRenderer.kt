package dev.farid.stabber.client.render

import dev.farid.stabber.client.path.MoveType
import dev.farid.stabber.client.path.PathResult
import dev.farid.stabber.client.path.StandingPositions
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos

object PathGizmoRenderer {
    private const val LINE_WIDTH = 3f
    private const val PERSIST_MS = 100
    private const val LIFT = 0.12

    private const val WALK = 0xFF44CCFF.toInt()
    private const val JUMP = 0xFFFFCC44.toInt()
    private const val DROP = 0xFFCC66FF.toInt()
    private const val INCOMPLETE = 0xFFFF5555.toInt()
    private val GOAL_OK = GizmoStyle.strokeAndFill(0xFF00FF88.toInt(), 2f, 0x3300FF88)
    private val GOAL_BAD = GizmoStyle.strokeAndFill(0xFFFF5555.toInt(), 2f, 0x33FF5555)

    fun render(path: PathResult) {
        if (path.nodes.isEmpty()) return

        val nodes = path.nodes
        for (i in 1 until nodes.size) {
            val from = nodes[i - 1]
            val to = nodes[i]
            val color = if (path.complete) colorFor(to.incoming) else INCOMPLETE
            val a = StandingPositions.nodeCentre(from.pos, from.floorY).add(0.0, LIFT, 0.0)
            val b = StandingPositions.nodeCentre(to.pos, to.floorY).add(0.0, LIFT, 0.0)
            Gizmos.line(a, b, color, LINE_WIDTH)
                .setAlwaysOnTop()
                .persistForMillis(PERSIST_MS)
        }

        val goalPos = path.goal ?: nodes.last().pos
        val style = if (path.complete) GOAL_OK else GOAL_BAD
        Gizmos.cuboid(goalPos, style)
            .setAlwaysOnTop()
            .persistForMillis(PERSIST_MS)
    }

    private fun colorFor(move: MoveType): Int {
        return when (move) {
            MoveType.WALK -> WALK
            MoveType.JUMP -> JUMP
            MoveType.DROP -> DROP
        }
    }
}
