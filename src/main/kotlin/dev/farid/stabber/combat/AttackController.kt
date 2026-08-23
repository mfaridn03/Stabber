package dev.farid.stabber.combat

import dev.farid.stabber.mixin.KeyMappingAccessor
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

/**
 * Injection surface for synthetic attack clicks. [FightBot] enqueues clicks from the render frame;
 * a mixin at the head of `Minecraft#handleKeybinds` drains them through `KeyMapping.click`, so
 * vanilla's own `while (keyAttack.consumeClick()) startAttack()` loop performs the attack exactly
 * as if the user had pressed the button: crosshair picking, miss swings, cooldowns and knockback
 * all stay vanilla.
 */
object AttackController {

    /** Upper bound on synthetic clicks delivered per client tick; extras are dropped. */
    const val MAX_CLICKS_PER_TICK: Int = 1

    private var pendingClicks = 0

    /** Called from the render frame when the click scheduler fires. */
    fun enqueue() {
        pendingClicks++
    }

    fun beforeHandleKeybinds(minecraft: Minecraft) {
        if (pendingClicks <= 0) return
        val clicks = pendingClicks.coerceAtMost(MAX_CLICKS_PER_TICK)
        pendingClicks = 0

        val player = minecraft.player ?: return
        if (minecraft.gui.screen() != null || player.isDeadOrDying) return

        val key = (minecraft.options.keyAttack as KeyMappingAccessor).stabberKey()
        repeat(clicks) { KeyMapping.click(key) }
    }
}
