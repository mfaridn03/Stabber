package dev.farid.stabber.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link KeyMapping}'s bound key so synthetic clicks can be routed through the static
 * {@code KeyMapping.click} path (identical to a real GLFW press) without touching click counters.
 * 26.2 removed the public getter for this field.
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("key")
    InputConstants.Key stabberKey();
}
