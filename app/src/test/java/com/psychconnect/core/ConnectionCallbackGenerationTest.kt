package com.psychconnect.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCallbackGenerationTest {
    @Test fun callbacksFromReplacedSocketsAreIgnored() {
        val generations=ConnectionCallbackGeneration()
        val old=generations.begin()
        assertTrue(generations.isCurrent(old))

        val active=generations.begin()
        assertFalse(generations.isCurrent(old))
        assertTrue(generations.isCurrent(active))
    }

    @Test fun closingInvalidatesPendingCallbacks() {
        val generations=ConnectionCallbackGeneration()
        val pending=generations.begin()
        generations.invalidate()
        assertFalse(generations.isCurrent(pending))
    }
}
