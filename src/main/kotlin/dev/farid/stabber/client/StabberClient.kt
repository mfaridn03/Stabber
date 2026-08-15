package dev.farid.stabber.client

import net.fabricmc.api.ClientModInitializer

class StabberClient : ClientModInitializer {

    override fun onInitializeClient() {
        StabberKeys.register()
    }
}
