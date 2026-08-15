package dev.farid.stabber.client.target

import dev.farid.stabber.client.path.PathfindingRegion
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.player.RemotePlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.zombie.Zombie

object AutoTargetScanner {
    const val ZOMBIE_MAX_HEALTH = 190f
    const val REMOTE_PLAYER_MAX_HEALTH = 210f

    fun isValidTarget(entity: LivingEntity): Boolean {
        if (!entity.isAlive || entity.isRemoved) return false
        if (!PathfindingRegion.contains(entity.blockPosition())) return false
        return when (entity) {
            is Zombie -> entity.maxHealth == ZOMBIE_MAX_HEALTH
            is RemotePlayer -> entity.maxHealth == REMOTE_PLAYER_MAX_HEALTH
            else -> false
        }
    }

    /**
     * Nearest valid targets by Euclidean distance, closest first, up to [limit].
     * Path length may still differ; caller should try A* in this order.
     */
    fun findClosest(level: ClientLevel, player: LocalPlayer, limit: Int = 5): List<LivingEntity> {
        if (limit <= 0) return emptyList()
        val scored = ArrayList<Pair<LivingEntity, Double>>()
        for (entity in level.entitiesForRendering()) {
            val living = entity as? LivingEntity ?: continue
            if (living === player) continue
            if (!isValidTarget(living)) continue
            scored.add(living to player.distanceToSqr(living))
        }
        scored.sortBy { it.second }
        return scored.take(limit).map { it.first }
    }
}
