package dev.farid.stabber.client

import com.mojang.brigadier.Command
import dev.farid.stabber.client.movement.PathFollower
import dev.farid.stabber.client.path.NodeEditController
import dev.farid.stabber.client.path.PathfindingController
import dev.farid.stabber.client.path.PathfindingRegion
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.network.chat.Component

object StabberCommands {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("pathfind").executes { context ->
                    val enabled = PathfindingController.togglePathfind(context.source.client)
                    val msg = if (enabled) "Pathfinding enabled" else "Pathfinding disabled"
                    context.source.sendFeedback(Component.literal(msg))
                    Command.SINGLE_SUCCESS
                },
            )
            dispatcher.register(
                ClientCommands.literal("edit").executes { context ->
                    val enabled = NodeEditController.toggleEdit()
                    val msg = if (enabled) "Node edit enabled" else "Node edit disabled"
                    context.source.sendFeedback(Component.literal(msg))
                    Command.SINGLE_SUCCESS
                },
            )
            dispatcher.register(
                ClientCommands.literal("start").executes {
                    PathFollower.requestStart()
                    Command.SINGLE_SUCCESS
                },
            )
            dispatcher.register(
                ClientCommands.literal("debug").executes { context ->
                    PathfindingRegion.debugMode = !PathfindingRegion.debugMode
                    val msg = if (PathfindingRegion.debugMode) "Debug mode enabled" else "Debug mode disabled"
                    context.source.sendFeedback(Component.literal(msg))
                    Command.SINGLE_SUCCESS
                }
            )
        }
    }
}
