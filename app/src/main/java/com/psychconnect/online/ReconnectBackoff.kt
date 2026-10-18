package com.psychconnect.online

/** Exponential reconnect delay capped at 30 seconds; reset once authentication succeeds. */
internal class ReconnectBackoff(private val initialDelayMs:Long=1_000L,private val maximumDelayMs:Long=30_000L) {
    private var failures=0

    init { require(initialDelayMs>0&&maximumDelayMs>=initialDelayMs) }

    fun nextDelayMs():Long {
        val exponent=failures.coerceAtMost(30)
        failures++
        return (initialDelayMs*(1L shl exponent)).coerceAtMost(maximumDelayMs)
    }

    fun reset() { failures=0 }
}
