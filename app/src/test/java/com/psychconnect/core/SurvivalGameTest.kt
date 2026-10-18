package com.psychconnect.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class SurvivalGameTest {
    @Test fun survivalClockAndDifficultyPauseWhenPlayerIsStill() {
        val engine=SurvivalGameEngine(Random(7))
        val player=Point(.5,.76)
        engine.reset(1_000L)

        engine.advance(21_000L,player,1080,1920,movementFactor=0.0)
        assertEquals(0L,engine.elapsedMs)
        assertEquals(0L,engine.hazardTimeMs)
        assertEquals(1,engine.level)
        assertEquals(0,engine.projectiles().size)
        assertEquals(0,engine.explosions().size)
    }

    @Test fun survivalScoreTimeAdvancesAlongsideMovementDrivenHazards() {
        val engine=SurvivalGameEngine(Random(7))
        val player=Point(.5,.76)
        engine.reset(0L)
        engine.advance(8_000L,player,1080,1920,movementFactor=0.0)
        engine.advance(9_000L,player,1080,1920,movementFactor=.5)
        assertEquals(500L,engine.elapsedMs)
        assertEquals(engine.elapsedMs,engine.hazardTimeMs)

        engine.advance(9_500L,player,1080,1920,movementFactor=0.0)
        assertEquals(500L,engine.elapsedMs)
        engine.advance(10_500L,player,1080,1920,movementFactor=1.0)
        assertEquals(1_500L,engine.elapsedMs)
        assertEquals(engine.elapsedMs,engine.hazardTimeMs)
    }

    @Test fun appBackgroundTimeDoesNotCountTowardSurvivalScore() {
        val engine=SurvivalGameEngine(Random(7))
        val player=Point(.5,.76)
        engine.reset(100L)
        engine.advance(1_100L,player,1080,1920,movementFactor=1.0)
        assertEquals(1_000L,engine.elapsedMs)

        engine.shiftTime(5_000L)
        engine.advance(6_100L,player,1080,1920,movementFactor=0.0)
        assertEquals(1_000L,engine.elapsedMs)
        engine.advance(6_600L,player,1080,1920,movementFactor=1.0)
        assertEquals(1_500L,engine.elapsedMs)
    }
}
