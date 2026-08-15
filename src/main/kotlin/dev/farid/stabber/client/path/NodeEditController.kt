package dev.farid.stabber.client.path

import dev.farid.stabber.client.StabberKeys
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

object NodeEditController {
    var editMode: Boolean = false
        private set

    var selectMode: Boolean = false
        private set

    var highlightedId: Int? = null
        private set

    fun toggleEdit(): Boolean {
        editMode = !editMode
        if (!editMode) {
            selectMode = false
            highlightedId = null
        }
        return editMode
    }

    fun tick(minecraft: Minecraft) {
        if (!editMode) {
            drainClicks()
            highlightedId = null
            return
        }
        val player = minecraft.player ?: return
        if (minecraft.gui.screen() != null) {
            drainClicks()
            return
        }

        if (selectMode) {
            highlightedId = ManualNodeGraph.nearestTo(player.position())?.id
        } else {
            highlightedId = null
        }

        while (StabberKeys.selectMode.consumeClick()) {
            selectMode = !selectMode
            val msg = if (selectMode) "Select mode on" else "Select mode off"
            player.sendSystemMessage(Component.literal(msg))
            if (!selectMode) highlightedId = null
        }
        while (StabberKeys.placeNode.consumeClick()) {
            place(minecraft, PlacementKind.NORMAL)
        }
        while (StabberKeys.placeJumpNode.consumeClick()) {
            place(minecraft, PlacementKind.JUMP)
        }
        while (StabberKeys.placeDropNode.consumeClick()) {
            place(minecraft, PlacementKind.DROP)
        }
        while (StabberKeys.removeNode.consumeClick()) {
            remove(minecraft)
        }
    }

    private fun place(minecraft: Minecraft, kind: PlacementKind) {
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val pos = player.blockPosition()
        if (!PathfindingRegion.contains(pos)) {
            player.sendSystemMessage(Component.literal("Outside pathfinding region"))
            return
        }
        val world = LevelPathingWorld(level)
        val floorY = StandingPositions.floorHeight(world, pos)
        if (floorY == null || !StandingPositions.hasClearance(world, pos, floorY)) {
            player.sendSystemMessage(Component.literal("Cannot stand here"))
            return
        }
        val placed = ManualNodeGraph.place(pos, floorY, kind)
        if (placed == null) {
            player.sendSystemMessage(Component.literal("Node already exists here"))
            return
        }
        NodeGraphStorage.save()
    }

    private fun remove(minecraft: Minecraft) {
        val player = minecraft.player ?: return
        if (!selectMode) return
        val id = highlightedId ?: return
        if (ManualNodeGraph.remove(id)) {
            NodeGraphStorage.save()
            highlightedId = ManualNodeGraph.nearestTo(player.position())?.id
        }
    }

    private fun drainClicks() {
        while (StabberKeys.placeNode.consumeClick()) {}
        while (StabberKeys.placeJumpNode.consumeClick()) {}
        while (StabberKeys.placeDropNode.consumeClick()) {}
        while (StabberKeys.removeNode.consumeClick()) {}
        while (StabberKeys.selectMode.consumeClick()) {}
    }
}
