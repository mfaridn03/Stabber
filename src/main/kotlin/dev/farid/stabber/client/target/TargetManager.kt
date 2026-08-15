package dev.farid.stabber.client.target

import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level

object TargetManager {
    var target: LivingEntity? = null
        private set

    fun isTarget(entity: Any?): Boolean {
        val current = target ?: return false
        return entity === current
    }

    fun select(entity: LivingEntity) {
        target = if (isTarget(entity)) null else entity
    }

    fun assign(entity: LivingEntity) {
        target = entity
    }

    fun clear() {
        target = null
    }

    fun validate(level: Level?): Boolean {
        val current = target ?: return false
        if (level == null || current.level() !== level || current.isRemoved || !current.isAlive) {
            clear()
            return false
        }
        return true
    }
}
