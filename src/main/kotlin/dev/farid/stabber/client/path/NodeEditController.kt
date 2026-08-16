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

    private var warnedPathfindingLock = false

    fun toggleEdit(): Boolean {
        editMode = !editMode
        if (!editMode) {
            selectMode = false
            highlightedId = null
            warnedPathfindingLock = false
        }
        return editMode
    }

    fun tick(minecraft: Minecraft) {
        if (!PathfindingController.active) {
            warnedPathfindingLock = false
        }
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
        if (PathfindingController.active) {
            highlightedId = null
            if (drainMutatingClicks() && !warnedPathfindingLock) {
                player.sendSystemMessage(Component.literal("Cannot edit nodes while pathfinding"))
                warnedPathfindingLock = true
            }
            while (StabberKeys.selectMode.consumeClick()) {}
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
        if (selectMode) {
            while (minecraft.options.keyShift.consumeClick()) {
                val id = highlightedId
                if (id != null && ManualNodeGraph.setCurrent(id)) {
                    NodeGraphStorage.save()
                }
            }
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
        if (PathfindingController.active) return
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
        ManualNodeGraph.placeOrConnect(pos, floorY, kind)
        NodeGraphStorage.save()
    }

    private fun remove(minecraft: Minecraft) {
        if (PathfindingController.active) return
        val player = minecraft.player ?: return
        if (!selectMode) return
        val id = highlightedId ?: return
        if (ManualNodeGraph.remove(id)) {
            NodeGraphStorage.save()
            highlightedId = ManualNodeGraph.nearestTo(player.position())?.id
        }
    }

    private fun drainMutatingClicks(): Boolean {
        var clicked = false
        while (StabberKeys.placeNode.consumeClick()) { clicked = true }
        while (StabberKeys.placeJumpNode.consumeClick()) { clicked = true }
        while (StabberKeys.placeDropNode.consumeClick()) { clicked = true }
        while (StabberKeys.removeNode.consumeClick()) { clicked = true }
        return clicked
    }

    private fun drainClicks() {
        drainMutatingClicks()
        while (StabberKeys.selectMode.consumeClick()) {}
    }
}
