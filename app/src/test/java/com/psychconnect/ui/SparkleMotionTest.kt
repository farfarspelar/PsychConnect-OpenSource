package com.psychconnect.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SparkleMotionTest {
    @Test fun sparkleFadesSlowlyWithinConfiguredRange() {
        val start=SparkleMotion.alphaAt(0,0,6_000,3,27)
        val peak=SparkleMotion.alphaAt(3_000,0,6_000,3,27)
        assertEquals(3,start)
        assertEquals(27,peak)
        assertEquals(start,SparkleMotion.alphaAt(6_000,0,6_000,3,27))
    }

    @Test fun eachSparkleCanHaveAStablePhaseOffset() {
        val early=SparkleMotion.alphaAt(0,0,6_000,3,27)
        val offset=SparkleMotion.alphaAt(0,1_500,6_000,3,27)
        assertTrue(offset>early)
    }

    @Test fun reducedMotionCanUseNoOverlayOpacity() {
        val alpha=SparkleMotion.alphaAt(5_000,0,9_000,0,0)
        assertEquals(0,alpha)
    }
}
