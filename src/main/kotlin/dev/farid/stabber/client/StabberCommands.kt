package dev.farid.stabber.client

import com.mojang.brigadier.Command
import dev.farid.stabber.client.path.PathfindingController
import dev.farid.stabber.client.target.TargetManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.network.chat.Component

object StabberCommands {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("pathfind").executes { context ->
                    val client = context.source.client
                    if (TargetManager.target == null) {
                        context.source.sendError(Component.literal("No target selected"))
                        return@executes 0
                    }
                    if (!PathfindingController.startPathfinding(client)) {
                        context.source.sendError(Component.literal("Cannot pathfind"))
                        return@executes 0
                    }
                    context.source.sendFeedback(Component.literal("Pathfinding started"))
                    Command.SINGLE_SUCCESS
                },
            )
        }
    }
}
