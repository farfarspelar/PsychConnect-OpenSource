package com.psychconnect.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundInputGateTest {
    @Test fun inputRemainsOpenUntilRoundDeadlineThenClosesPermanently() {
        val gate=RoundInputGate()
        gate.open()
        assertTrue(gate.update(29_999L,30_000L))
        assertFalse(gate.update(30_000L,30_000L))
        assertFalse(gate.update(30_010L,30_000L))
    }

    @Test fun closingRoundDisablesInputImmediately() {
        val gate=RoundInputGate()
        gate.open()
        gate.close()
        assertFalse(gate.update(0L,30_000L))
    }

    @Test fun untimedHarmonyDoesNotUseTheRoundDeadline() {
        val gate=RoundInputGate()
        assertTrue(gate.permits("HARMONY",false,60_000L,0L))
        assertTrue(gate.permits("HARMONY",false,600_000L,0L))
    }
}
