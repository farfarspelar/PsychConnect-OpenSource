package com.psychconnect.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

data class SurvivalProjectile(val point: Point, val velocityX: Double, val velocityY: Double)
data class SurvivalExplosion(val center: Point, val detonatesAtMs: Long, val durationMs: Long, val maxRadius: Double)

/** Local, single-player hazard simulation. Coordinates are normalized and independent of Android views. */
class SurvivalGameEngine(private val random: Random = Random.Default) {
    private val projectiles = mutableListOf<SurvivalProjectile>()
    private val explosions = mutableListOf<SurvivalExplosion>()
    private var initialized = false
    private var updatedAtMs = 0L
    private var hazardNowMs = 0L
    private var activeElapsedMs = 0L
    private var movementRemainderMs = 0.0
    private var nextProjectileAtMs = 0L
    private var nextExplosionAtMs = 0L

    /** Survival score time advances only with movement-driven hazard time. */
    val elapsedMs: Long get() = activeElapsedMs
    val hazardTimeMs: Long get() = hazardNowMs
    val level: Int get() = 1 + (elapsedMs / 20_000L).toInt()
    fun projectiles(): List<SurvivalProjectile> = projectiles.toList()
    fun explosions(): List<SurvivalExplosion> = explosions.toList()

    fun reset(nowMs: Long) {
        initialized = true
        updatedAtMs = nowMs
        hazardNowMs = 0L
        activeElapsedMs = 0L
        movementRemainderMs = 0.0
        nextProjectileAtMs = hazardNowMs + 1_700L
        nextExplosionAtMs = hazardNowMs + 3_500L
        projectiles.clear()
        explosions.clear()
    }

    /** Shifts the frame clock when Android pauses the run in the background. */
    fun shiftTime(deltaMs: Long) {
        if (deltaMs <= 0L) return
        updatedAtMs += deltaMs
    }

    /** Both score time and hazards advance in proportion to player movement. */
    fun advance(nowMs: Long, player: Point, widthPx: Int, heightPx: Int, movementFactor:Double=1.0): Boolean {
        if (widthPx <= 0 || heightPx <= 0) return false
        if (!initialized) reset(nowMs)
        val now = nowMs.coerceAtLeast(updatedAtMs)
        val aspectX = widthPx.toDouble() / minOf(widthPx, heightPx)
        val aspectY = heightPx.toDouble() / minOf(widthPx, heightPx)
        val previousHazardNow=hazardNowMs
        val wallDelta=(now-updatedAtMs).coerceAtLeast(0L)
        val activeDelta=wallDelta*movementFactor.coerceIn(0.0,1.0)+movementRemainderMs
        val activeDeltaMs=activeDelta.toLong()
        movementRemainderMs=activeDelta-activeDeltaMs
        activeElapsedMs+=activeDeltaMs
        hazardNowMs+=activeDeltaMs
        val elapsedSeconds = activeElapsedMs / 1_000.0
        val hazardNow=hazardNowMs
        val projectileInterval = (2_600.0 / (1.0 + elapsedSeconds / 32.0)).toLong().coerceAtLeast(520L)
        val explosionInterval = (7_800.0 / (1.0 + elapsedSeconds / 38.0)).toLong().coerceAtLeast(2_200L)

        // A delayed UI frame should delay a hazard, not release several at once.
        if (hazardNow >= nextProjectileAtMs) {
            projectiles += newProjectile(player, aspectX, aspectY)
            nextProjectileAtMs = hazardNow + projectileInterval
        }

        if (hazardNow >= nextExplosionAtMs) {
            val burstRoll = random.nextDouble()
            val burst = when {
                burstRoll < 0.025 -> 3
                burstRoll < 0.185 -> 2
                else -> 1
            }
            repeat(burst) { index ->
                val center = safeExplosionCenter(player, aspectX, aspectY)
                explosions += SurvivalExplosion(center, hazardNow + 850L + index * 180L, 2_300L, random.nextDouble(0.72, 0.94))
            }
            nextExplosionAtMs = hazardNow + explosionInterval
        }

        val dt = (hazardNow-previousHazardNow) / 1_000.0
        updatedAtMs = now
        // One gradual speed curve avoids multiplying two independent ramps.
        val speedFactor = (1.0 + elapsedSeconds / 68.0).coerceAtMost(2.35)
        var projectileHit = false
        for (index in projectiles.indices) {
            val item = projectiles[index]
            val next = Point(item.point.x + item.velocityX * speedFactor * dt, item.point.y + item.velocityY * speedFactor * dt)
            if (distanceToSegment(player, item.point, next, aspectX, aspectY) < 0.052) projectileHit = true
            projectiles[index] = item.copy(point = next)
        }
        projectiles.removeAll { it.point.x < -0.15 || it.point.x > 1.15 || it.point.y < -0.15 || it.point.y > 1.15 }
        explosions.removeAll { hazardNow >= it.detonatesAtMs + it.durationMs }

        if (projectileHit) return true
        return explosions.any { blast ->
            if (hazardNow < blast.detonatesAtMs) false else {
                val progress = ((hazardNow - blast.detonatesAtMs).toDouble() / blast.durationMs).coerceIn(0.0, 1.0)
                val radius = blast.maxRadius * progress
                val distance = distanceOnScreen(player, blast.center, aspectX, aspectY)
                val previousRadius = if (previousHazardNow <= blast.detonatesAtMs) 0.0 else
                    blast.maxRadius * ((previousHazardNow - blast.detonatesAtMs).toDouble() / blast.durationMs).coerceIn(0.0, 1.0)
                distance >= previousRadius - 0.045 && distance <= radius + 0.045
            }
        }
    }

    private fun safeExplosionCenter(player: Point, aspectX: Double, aspectY: Double): Point {
        var best = Point(.5, .5)
        var bestDistance = -1.0
        repeat(12) {
            val candidate = Point(random.nextDouble(0.16, 0.84), random.nextDouble(0.14, 0.86))
            val distance = distanceOnScreen(candidate, player, aspectX, aspectY)
            if (distance > bestDistance) {
                best = candidate
                bestDistance = distance
            }
            if (distance >= 0.22) return candidate
        }
        return best
    }

    private fun newProjectile(target: Point, aspectX: Double, aspectY: Double): SurvivalProjectile {
        val side = random.nextInt(4)
        val origin = when (side) {
            0 -> Point(-0.04, random.nextDouble())
            1 -> Point(1.04, random.nextDouble())
            2 -> Point(random.nextDouble(), -0.04)
            else -> Point(random.nextDouble(), 1.04)
        }
        val dx = (target.x - origin.x) * aspectX
        val dy = (target.y - origin.y) * aspectY
        val length = hypot(dx, dy).coerceAtLeast(0.001)
        val aimJitter = random.nextDouble(-0.34, 0.34)
        val dirX = (dx / length) * cos(aimJitter) - (dy / length) * sin(aimJitter)
        val dirY = (dx / length) * sin(aimJitter) + (dy / length) * cos(aimJitter)
        // Normalized screen lengths per second. Difficulty is applied once in advance().
        val speed = 0.29
        return SurvivalProjectile(origin, dirX * speed / aspectX, dirY * speed / aspectY)
    }

    private fun distanceToSegment(point: Point, start: Point, end: Point, aspectX: Double, aspectY: Double): Double {
        val px = point.x * aspectX
        val py = point.y * aspectY
        val sx = start.x * aspectX
        val sy = start.y * aspectY
        val dx = (end.x - start.x) * aspectX
        val dy = (end.y - start.y) * aspectY
        val lengthSquared = dx * dx + dy * dy
        val progress = if (lengthSquared <= 1e-12) 0.0 else
            (((px - sx) * dx + (py - sy) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(px - (sx + progress * dx), py - (sy + progress * dy))
    }

    private fun distanceOnScreen(a: Point, b: Point, aspectX: Double, aspectY: Double): Double =
        hypot((a.x - b.x) * aspectX, (a.y - b.y) * aspectY)
}
