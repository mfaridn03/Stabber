package dev.farid.stabber.client

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

object StabberKeys {
    val selectTarget: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.stabber.select_target",
            InputConstants.Type.MOUSE,
            2,
            KeyMapping.Category.GAMEPLAY,
        )
    )

    val placeNode: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.stabber.place_node",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            KeyMapping.Category.GAMEPLAY,
        )
    )

    val removeNode: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.stabber.remove_node",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            KeyMapping.Category.GAMEPLAY,
        )
    )

    val placeJumpNode: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.stabber.place_jump_node",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            KeyMapping.Category.GAMEPLAY,
        )
    )

    val placeDropNode: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.stabber.place_drop_node",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            KeyMapping.Category.GAMEPLAY,
        )
    )

    val selectMode: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.stabber.select_mode",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            KeyMapping.Category.GAMEPLAY,
        )
    )

    fun register() {
        selectTarget
        placeNode
        removeNode
        placeJumpNode
        placeDropNode
        selectMode
    }
}
