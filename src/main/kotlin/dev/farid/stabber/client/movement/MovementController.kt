package dev.farid.stabber.client.movement

/**
 * Holds desired movement key state for injection via [KeyboardInputMixin].
 * Real key presses are OR'd in the mixin so manual input still works.
 */
object MovementController {
    var active: Boolean = false
        private set

    var forward: Boolean = false
        private set

    var backward: Boolean = false
        private set

    var left: Boolean = false
        private set

    var right: Boolean = false
        private set

    var sprint: Boolean = false
        private set

    var sneak: Boolean = false
        private set

    private var jumpQueued: Boolean = false

    fun apply(
        forward: Boolean = false,
        backward: Boolean = false,
        left: Boolean = false,
        right: Boolean = false,
        sprint: Boolean = false,
        sneak: Boolean = false,
        jump: Boolean = false,
    ) {
        active = true
        this.forward = forward
        this.backward = backward
        this.left = left
        this.right = right
        this.sprint = sprint
        this.sneak = sneak
        if (jump) {
            jumpQueued = true
        }
    }

    fun requestJump() {
        active = true
        jumpQueued = true
    }

    /** Consumes and clears a one-shot jump request. */
    fun consumeJump(): Boolean {
        if (!jumpQueued) return false
        jumpQueued = false
        return true
    }

    fun release() {
        active = false
        forward = false
        backward = false
        left = false
        right = false
        sprint = false
        sneak = false
        jumpQueued = false
    }
}
