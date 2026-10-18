package com.psychconnect.online

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test fun delayDoublesAndCapsThenResetsAfterAuthentication() {
        val backoff=ReconnectBackoff()
        assertEquals(1_000L,backoff.nextDelayMs())
        assertEquals(2_000L,backoff.nextDelayMs())
        assertEquals(4_000L,backoff.nextDelayMs())
        repeat(8) { backoff.nextDelayMs() }
        assertEquals(30_000L,backoff.nextDelayMs())
        backoff.reset()
        assertEquals(1_000L,backoff.nextDelayMs())
    }
}
