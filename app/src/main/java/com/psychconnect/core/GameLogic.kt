package com.psychconnect.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.roundToLong

data class Point(val x: Double, val y: Double) {
    fun distanceTo(other: Point): Double = hypot(x - other.x, y - other.y).coerceAtMost(1.41421356237)
    companion object {
        fun normalize(xPx: Float, yPx: Float, width: Int, height: Int): Point {
            require(width > 0 && height > 0)
            return Point((xPx / width).coerceIn(0f, 1f).toDouble(), (yPx / height).coerceIn(0f, 1f).toDouble())
        }
    }
}

data class HeartbeatConfig(val restingBpm: Double = 65.0, val maxBpm: Double = 150.0,
                           val maxDistance: Double = 0.65, val smoothing: Double = 0.18,
                           val syncThreshold: Double = 0.035, val fasterWhenClose: Boolean = false) {
    init { require(restingBpm in 40.0..100.0 && maxBpm in 100.0..180.0 && maxDistance > 0 && smoothing in 0.0..1.0) }
}

data class Heartbeat(val bpm: Double, val synchronized: Boolean)

class HeartbeatMapper(private val config: HeartbeatConfig = HeartbeatConfig()) {
    private var smoothed = config.restingBpm
    fun update(distance: Double): Heartbeat {
        val d = distance.coerceAtLeast(0.0)
        // Both directions use the same smooth curve; the preference only reverses its meaning.
        val normalized = (d / config.maxDistance).coerceIn(0.0, 1.0)
        val response = if(config.fasterWhenClose) 1.0-normalized else normalized
        val target = config.restingBpm + (config.maxBpm - config.restingBpm) * response * response
        smoothed += (target - smoothed) * config.smoothing
        return Heartbeat(smoothed, d <= config.syncThreshold)
    }
    fun reset() { smoothed = config.restingBpm }
}

const val MAX_HARMONY_TOUCHES=5
data class HarmonyTouch(val id:Int,val point:Point,val sizeClass:Int=1)
fun harmonyTouchSnapshot(points:List<HarmonyTouch>):List<HarmonyTouch> = points.distinctBy { it.id }.take(MAX_HARMONY_TOUCHES)

/** Coarse remote marker size; raw device measurements stay on the phone. */
fun harmonyFingerSizeClass(measurement:PalmTouchMeasurement?,screenShortDimensionPx:Int):Int {
    if(measurement==null||screenShortDimensionPx<=0) return 1
    val major=measurement.touchMajor?.takeIf { it.isFinite()&&it>0f&&it<=screenShortDimensionPx*.45f }
        ?.div(screenShortDimensionPx)
    val size=measurement.size?.takeIf { it.isFinite()&&it in 0.0f..1.0f&&it>0f }?.toDouble()
    val normalized=major?.toDouble()?:size?:return 1
    return when { normalized<.045->0; normalized>.085->2; else->1 }
}
fun harmonyTouchIdsAfterPointerUp(trackedIds:List<Int>,eventIds:List<Int>,liftedId:Int):List<Int> {
    val remaining=trackedIds.filter { it!=liftedId }.distinct().take(MAX_HARMONY_TOUCHES)
    val promoted=eventIds.asSequence().filter { it!=liftedId && it !in remaining }.distinct().take(MAX_HARMONY_TOUCHES-remaining.size)
    return (remaining+promoted).take(MAX_HARMONY_TOUCHES)
}

data class TouchState(val active: Boolean, val point: Point?, val timestampMs: Long, val sequence: Long)

/** Stops accepting gameplay input once the local round clock reaches its deadline. */
class RoundInputGate {
    var isOpen: Boolean = false; private set
    fun open() { isOpen=true }
    fun close() { isOpen=false }
    fun permits(mode:String,simulation:Boolean,elapsedMs:Long,durationMs:Long):Boolean {
        if(mode in setOf("SURVIVAL","COOP_SURVIVAL","PUZZLE")) return isOpen
        if(simulation||mode=="HARMONY") return true
        return update(elapsedMs,durationMs)
    }
    fun update(elapsedMs:Long,durationMs:Long):Boolean {
        require(elapsedMs>=0L && durationMs>0L)
        if(elapsedMs>=durationMs) isOpen=false
        return isOpen
    }
}

class RemoteTouchTracker {
    var active: Boolean = false; private set
    var point: Point? = null; private set
    private var lastSequence = -1L
    /** Returns false for the server's own-player acknowledgement, which is not the opponent's state. */
    fun update(localPlayerId: String?, senderId: String, isActive: Boolean, newPoint: Point?, sequence: Long? = null): Boolean {
        if(localPlayerId!=null && senderId==localPlayerId) return false
        if(sequence!=null && sequence<=lastSequence) return false
        if(sequence!=null) lastSequence=sequence
        active=isActive; if(isActive && newPoint!=null) point=newPoint
        return true
    }
    fun reset() { active=false; point=null; lastSequence=-1L }
}

data class NetworkSnapshot(val rttMs: Long?, val jitterMs: Long?, val updateAgeMs: Long?)

/** Estimates RTT variation and server-to-device update age from ping/pong clock samples. */
class NetworkDiagnostics {
    private var previousRttMs: Double? = null
    private var rttMs: Double? = null
    private var jitterMs = 0.0
    private var serverMinusClientMs: Double? = null
    @Synchronized fun reset() { previousRttMs=null; rttMs=null; jitterMs=0.0; serverMinusClientMs=null }
    @Synchronized fun recordPong(clientSentMs: Long, serverReceivedMs: Double, clientReceivedMs: Long): Boolean {
        if(clientReceivedMs<clientSentMs || !serverReceivedMs.isFinite()) return false
        val rtt=(clientReceivedMs-clientSentMs).toDouble()
        if(rtt>60_000.0) return false
        previousRttMs?.let { jitterMs += (abs(rtt-it)-jitterMs)/16.0 }
        previousRttMs=rtt; rttMs=rtt
        val measuredOffset=serverReceivedMs-(clientSentMs+clientReceivedMs)/2.0
        serverMinusClientMs=serverMinusClientMs?.let { it+(measuredOffset-it)*0.2 } ?: measuredOffset
        return true
    }
    @Synchronized fun updateAge(serverTimestampMs: Double, clientNowMs: Long): Long? {
        val offset=serverMinusClientMs ?: return null
        if(!serverTimestampMs.isFinite()) return null
        val age=(clientNowMs+offset-serverTimestampMs).roundToLong()
        return if(age in -100L..60_000L) age.coerceAtLeast(0L) else null
    }
    @Synchronized fun estimatedServerTime(clientNowMs: Long): Double? = serverMinusClientMs?.let { clientNowMs+it }
    @Synchronized fun interpolationDelayMs(): Long = (50.0+2.0*jitterMs).roundToLong().coerceIn(80L,240L)
    @Synchronized fun snapshot(serverTimestampMs: Double? = null, clientNowMs: Long = 0L): NetworkSnapshot =
        NetworkSnapshot(rttMs?.roundToLong(), if(previousRttMs==null) null else jitterMs.roundToLong(), serverTimestampMs?.let { updateAge(it,clientNowMs) })
}

/** Small ordered buffer used to smooth remote touch positions against server time. */
class RemotePositionBuffer(private val capacity: Int = 32) {
    private data class Entry(val timeMs: Double, val point: Point)
    private val entries=ArrayList<Entry>()
    init { require(capacity>=2) }
    fun add(timeMs: Double, point: Point): Boolean {
        if(!timeMs.isFinite() || (entries.lastOrNull()?.timeMs?.let { timeMs<=it }==true)) return false
        entries.add(Entry(timeMs,point))
        while(entries.size>capacity) entries.removeAt(0)
        return true
    }
    fun sampleAt(timeMs: Double): Point? {
        if(entries.isEmpty() || !timeMs.isFinite()) return null
        if(timeMs<=entries.first().timeMs) return entries.first().point
        for(i in 0 until entries.lastIndex) {
            val before=entries[i]; val after=entries[i+1]
            if(timeMs<=after.timeMs) {
                val fraction=((timeMs-before.timeMs)/(after.timeMs-before.timeMs)).coerceIn(0.0,1.0)
                return Point(before.point.x+(after.point.x-before.point.x)*fraction,before.point.y+(after.point.y-before.point.y)*fraction)
            }
        }
        return entries.last().point
    }
    fun clear()=entries.clear()
    val size get()=entries.size
}

class TouchTracker {
    private var activePointer: Int? = null
    private var sequence = 0L
    var state = TouchState(false, null, 0, 0); private set
    fun down(pointerId: Int, point: Point, timestampMs: Long): Boolean {
        if (activePointer != null) return false
        activePointer = pointerId; publish(true, point, timestampMs); return true
    }
    fun move(pointerId: Int, point: Point, timestampMs: Long) { if (activePointer == pointerId) publish(true, point, timestampMs) }
    fun up(pointerId: Int, timestampMs: Long) { if (activePointer == pointerId) { activePointer = null; publish(false, null, timestampMs) } }
    fun cancel(timestampMs: Long) { if (activePointer != null) { activePointer = null; publish(false, null, timestampMs) } }
    private fun publish(active: Boolean, point: Point?, time: Long) { sequence++; state = TouchState(active, point, time, sequence) }
}

data class LimitedMovement(val point: Point, val limited: Boolean)
class SpeedLimiter(private val maxUnitsPerSecond: Double = 0.5) {
    private var previous: Point? = null
    private var previousTime: Long? = null
    init { require(maxUnitsPerSecond > 0.0) }
    fun update(target: Point, timestampMs: Long): LimitedMovement {
        val from=previous; val at=previousTime
        if(from==null || at==null || timestampMs<at) { previous=target; previousTime=timestampMs; return LimitedMovement(target,false) }
        val allowed=maxUnitsPerSecond*((timestampMs-at).coerceIn(1L,250L)/1000.0)
        val dx=target.x-from.x; val dy=target.y-from.y; val distance=kotlin.math.hypot(dx,dy)
        val result=if(distance>allowed) Point(from.x+dx*allowed/distance,from.y+dy*allowed/distance) else target
        previous=result; previousTime=timestampMs
        return LimitedMovement(result,distance>allowed)
    }
    fun reset() { previous=null; previousTime=null }
}

data class ScoreResult(val score: Int, val alignmentPercent: Int, val synchronizedSeconds: Double, val longestSyncSeconds: Double = 0.0)
data class Sample(val timeMs: Long, val distance: Double, val leaderSpeed: Double = 0.0)
object SharedConnectionScoring {
    fun step(score: Double, distance: Double, leaderMoving: Boolean, followerMoving: Boolean, elapsedMs: Long): Double {
        val elapsed=elapsedMs.coerceIn(0L,250L)/1000.0
        val closeness=1.0-(distance.coerceAtLeast(0.0)/0.65).coerceIn(0.0,1.0)
        val change=if(leaderMoving && followerMoving) elapsed*7.0*closeness*closeness else -elapsed*3.5
        return (score+change).coerceIn(0.0,100.0)
    }
}
object ScoringEngine {
    fun score(samples: List<Sample>, startMs: Long, endMs: Long, syncDistance: Double = 0.06): ScoreResult {
        if (endMs <= startMs || samples.isEmpty()) return ScoreResult(0, 0, 0.0, 0.0)
        val sorted = samples.filter { it.timeMs in startMs..endMs }.sortedBy { it.timeMs }
        if (sorted.isEmpty()) return ScoreResult(0, 0, 0.0, 0.0)
        val duration = (endMs - startMs).toDouble()
        val weighted = sorted.sumOf { (1.0 - (it.distance / 0.8).coerceIn(0.0, 1.0)) } / sorted.size
        val aligned = sorted.count { it.distance <= syncDistance }.toDouble() / sorted.size
        val stable = 1.0 - min(1.0, sorted.zipWithNext().sumOf { (_, b) -> max(0.0, b.distance - syncDistance) } / max(1, sorted.size - 1) / 0.35)
        val score = ((weighted * 0.55 + aligned * 0.30 + stable * 0.15) * 100).toInt().coerceIn(0, 100)
        val intervals=sorted.zipWithNext().map { (a,b) -> (b.timeMs-a.timeMs).coerceIn(0L,250L) }
        val typical=intervals.sorted().let { if(it.isEmpty()) 50L else it[it.size/2] }
        val syncMs=sorted.indices.sumOf { i ->
            val next=if(i<sorted.lastIndex) intervals[i] else typical
            if(sorted[i].distance<=syncDistance) next.coerceAtMost((endMs-sorted[i].timeMs).coerceAtLeast(0L)) else 0L
        }
        var currentStreak=0L; var longestStreak=0L
        sorted.indices.forEach { i ->
            val next=if(i<sorted.lastIndex) intervals[i] else typical.coerceAtMost((endMs-sorted[i].timeMs).coerceAtLeast(0L))
            if(sorted[i].distance<=syncDistance) { currentStreak+=next; longestStreak=max(longestStreak,currentStreak) }
            else currentStreak=0L
        }
        return ScoreResult(score, (aligned * 100).toInt(), syncMs / 1000.0, longestStreak / 1000.0)
    }
}

enum class RoundPhase { WAITING, READY, ACTIVE, RESULT, MATCH_OVER }
class RoundStateMachine {
    var phase = RoundPhase.WAITING; private set
    var round = 1; private set
    fun playersReady() { if (phase == RoundPhase.WAITING) phase = RoundPhase.READY }
    fun start() { if (phase == RoundPhase.READY) phase = RoundPhase.ACTIVE }
    fun finish(matchComplete: Boolean = false) { if (phase == RoundPhase.ACTIVE) phase = if (matchComplete) RoundPhase.MATCH_OVER else RoundPhase.RESULT }
    fun nextRound() { if (phase == RoundPhase.RESULT) { round++; phase = RoundPhase.READY } }
    fun reset() { phase = RoundPhase.WAITING; round = 1 }
}
