package dev.farid.stabber.mixin;

import dev.farid.stabber.client.target.TargetManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin {
    @Inject(method = "getTeamColor", at = @At("RETURN"), cancellable = true)
    private void stabber$targetOutlineColor(CallbackInfoReturnable<Integer> cir) {
        if (TargetManager.INSTANCE.isTarget(this)) {
            cir.setReturnValue(0x00FF88);
        }
    }
}
