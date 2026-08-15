package dev.farid.stabber.client

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping

object StabberKeys {
    val selectTarget: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.stabber.select_target",
            InputConstants.Type.MOUSE,
            2,
            KeyMapping.Category.GAMEPLAY,
        )
    )

    fun register() {
        selectTarget
    }
}
