package dev.farid.stabber.client

import dev.farid.stabber.client.movement.PathFollower
import dev.farid.stabber.client.path.PathfindingController
import dev.farid.stabber.client.render.PathGizmoRenderer
import dev.farid.stabber.client.target.TargetManager
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft

class StabberClient : ClientModInitializer {

    override fun onInitializeClient() {
        StabberKeys.register()
        StabberCommands.register()
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            PathfindingController.tick(client)
            PathFollower.tick(client)
        }
        LevelRenderEvents.BEFORE_GIZMOS.register {
            val levelRenderer = Minecraft.getInstance().levelRenderer
            levelRenderer.collectPerFrameRenderThreadGizmos().use {
                PathGizmoRenderer.render(PathfindingController.path, TargetManager.target)
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            PathfindingController.onDisconnect()
            PathFollower.stop()
        }
    }
}
