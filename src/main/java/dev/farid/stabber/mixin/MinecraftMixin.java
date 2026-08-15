package dev.farid.stabber.mixin;

import dev.farid.stabber.client.target.TargetManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("RETURN"), cancellable = true)
    private void stabber$glowTarget(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (TargetManager.INSTANCE.isTarget(entity)) {
            cir.setReturnValue(true);
        }
    }
}
