package dev.farid.stabber.mixin;

import dev.farid.stabber.client.movement.MovementController;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After vanilla reads real keys, OR in [MovementController] state and rebuild the move vector.
 * Manual key presses still win for stopping or overriding direction.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void stabber$overrideInput(CallbackInfo ci) {
        if (!MovementController.INSTANCE.getActive()) {
            return;
        }

        boolean forward = this.keyPresses.forward() || MovementController.INSTANCE.getForward();
        boolean backward = this.keyPresses.backward() || MovementController.INSTANCE.getBackward();
        boolean left = this.keyPresses.left() || MovementController.INSTANCE.getLeft();
        boolean right = this.keyPresses.right() || MovementController.INSTANCE.getRight();
        boolean jump = this.keyPresses.jump() || MovementController.INSTANCE.consumeJump();
        boolean shift = this.keyPresses.shift() || MovementController.INSTANCE.getSneak();
        boolean sprint = this.keyPresses.sprint() || MovementController.INSTANCE.getSprint();

        this.keyPresses = new Input(forward, backward, left, right, jump, shift, sprint);
        float forwardImpulse = calculateImpulse(forward, backward);
        float leftImpulse = calculateImpulse(left, right);
        this.moveVector = new Vec2(leftImpulse, forwardImpulse).normalized();
    }

    private static float calculateImpulse(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }
}
