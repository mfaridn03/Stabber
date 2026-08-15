package dev.farid.stabber.client

import dev.farid.stabber.client.path.PathfindingController
import dev.farid.stabber.client.render.PathGizmoRenderer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

class StabberClient : ClientModInitializer {

    override fun onInitializeClient() {
        StabberKeys.register()
        StabberCommands.register()
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            PathfindingController.tick(client)
            PathGizmoRenderer.render(PathfindingController.path)
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            PathfindingController.onDisconnect()
        }
    }
}
