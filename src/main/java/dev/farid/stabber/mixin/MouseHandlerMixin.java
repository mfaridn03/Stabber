package dev.farid.stabber.mixin;

import dev.farid.stabber.client.rotation.RotationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.SmoothDouble;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    @Shadow
    @Final
    private SmoothDouble smoothTurnX;

    @Shadow
    @Final
    private SmoothDouble smoothTurnY;

    /**
     * Replaces the accumulated mouse movement with the deltas needed to reach the rotation target,
     * so the rest of vanilla's turn handling applies it as if the user had moved the mouse. Real
     * mouse input is discarded while a rotation is active.
     */
    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void stabber$applyRotationTarget(double mousea, CallbackInfo ci) {
        if (!RotationController.INSTANCE.isRotating()) {
            return;
        }
        LocalPlayer player = this.minecraft.player;
        if (player == null) {
            return;
        }

        // Smoothing state is fed from real mouse movement, which we are about to discard. Resetting
        // it also makes the smooth-camera branch below predictable: from a clean state,
        // SmoothDouble returns half of the requested delta scaled by the frame time.
        this.smoothTurnX.reset();
        this.smoothTurnY.reset();

        double sensitivity = this.minecraft.options.sensitivity().get() * 0.6 + 0.2;
        double sensitivityMod = sensitivity * sensitivity * sensitivity;
        double sens = sensitivityMod * 8.0;
        double unitScale;
        if (this.minecraft.options.smoothCamera) {
            unitScale = 0.15 * sens * sens * mousea * 0.5;
        } else if (this.minecraft.options.getCameraType().isFirstPerson() && player.isScoping()) {
            unitScale = 0.15 * sensitivityMod;
        } else {
            unitScale = 0.15 * sens;
        }
        double degreesPerUnitX = this.minecraft.options.invertMouseX().get() ? -unitScale : unitScale;
        double degreesPerUnitY = this.minecraft.options.invertMouseY().get() ? -unitScale : unitScale;

        RotationController.Step step =
            RotationController.INSTANCE.consumeFrameDelta(player, degreesPerUnitX, degreesPerUnitY);
        if (step == null) {
            return;
        }

        this.accumulatedDX = step.getDx();
        this.accumulatedDY = step.getDy();
    }
}
