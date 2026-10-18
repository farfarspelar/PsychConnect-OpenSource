package com.psychconnect.core

import kotlin.math.max
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

data class HarmonyHapticShape(val strength:Float,val pulseDurationMs:Long,val tempoOffsetBpm:Double,val palmMode:PalmFeedbackMode=PalmFeedbackMode.FINGER)
data class HarmonyHapticConfig(
    val baseStrength:Double=0.68,
    val extraFingerStrength:Double=0.45,
    val movementStrength:Double=0.62,
    val basePulseDurationMs:Double=36.0,
    val extraFingerDurationMs:Double=16.0,
    val movementDurationMs:Double=34.0,
    val maxMovementSpeed:Double=0.7,
    val maxPulseDurationMs:Long=78L,
    val maxTempoOffsetBpm:Double=10.0,
    val anchorStrengthScale:Double=0.55,
    val anchorExtraDurationMs:Long=12L,
    val anchorTempoOffsetBpm:Double=-10.0,
    val remoteFingerStrength:Double=0.18,
    val remoteMovementStrength:Double=0.2,
    val remoteMovementDurationMs:Double=10.0,
    val maxStrength:Double=1.8,
    val maxRotationSpeedRadPerSec:Double=8.0,
    val rotationStrength:Double=0.35,
    val remoteRotationStrength:Double=0.15,
    val rotationPulseDurationMs:Double=12.0,
    val rotationTempoOffsetBpm:Double=5.0,
    val localPalmStrength:Double=.62,
    val remotePalmStrength:Double=.48,
    val sharedPalmStrength:Double=.78,
    val palmPulseDurationMs:Long=30L,
    val sharedPalmTempoOffsetBpm:Double=6.0
) {
    init {
        require(baseStrength >= 0.0 && extraFingerStrength >= 0.0 && movementStrength >= 0.0)
        require(basePulseDurationMs >= 30.0 && extraFingerDurationMs >= 0.0 && movementDurationMs >= 0.0)
        require(maxMovementSpeed > 0.0 && maxPulseDurationMs >= 30L && maxTempoOffsetBpm >= 0.0)
        require(anchorStrengthScale in 0.0..1.0 && anchorExtraDurationMs >= 0L)
        require(anchorTempoOffsetBpm <= 0.0)
        require(remoteFingerStrength >= 0.0 && remoteMovementStrength >= 0.0 && remoteMovementDurationMs >= 0.0)
        require(maxStrength >= baseStrength)
        require(maxRotationSpeedRadPerSec > 0.0 && rotationStrength >= 0.0 && remoteRotationStrength >= 0.0)
        require(rotationPulseDurationMs >= 0.0 && rotationTempoOffsetBpm >= 0.0)
        require(localPalmStrength in 0.0..maxStrength && remotePalmStrength in 0.0..maxStrength && sharedPalmStrength in 0.0..maxStrength)
        require(palmPulseDurationMs in 24L..60L && sharedPalmTempoOffsetBpm >= 0.0)
    }
}

/** Converts palm synchronization state into a restrained, short-pulse heartbeat texture. */
class HarmonyPalmHapticPattern(private val config:HarmonyHapticConfig=HarmonyHapticConfig()) {
    fun create(feedback:PalmFeedback):HarmonyHapticShape {
        val strength=when(feedback.mode) {
            PalmFeedbackMode.LOCAL_PALM->config.localPalmStrength
            PalmFeedbackMode.REMOTE_PALM->config.remotePalmStrength
            PalmFeedbackMode.SHARED_PALM->config.localPalmStrength+(config.sharedPalmStrength-config.localPalmStrength)*feedback.sharedBlend.coerceIn(0.0,1.0)
            PalmFeedbackMode.FINGER->return HarmonyHapticShape(0f,0L,0.0)
        }
        val tempo=if(feedback.mode==PalmFeedbackMode.SHARED_PALM) config.sharedPalmTempoOffsetBpm*feedback.sharedBlend.coerceIn(0.0,1.0) else 0.0
        return HarmonyHapticShape(strength.toFloat(),config.palmPulseDurationMs,tempo,feedback.mode)
    }
}

/** Maps touch count and motion energy to a restrained, tunable heartbeat texture. */
class HarmonyHapticMapper(private val config:HarmonyHapticConfig=HarmonyHapticConfig()) {
    private val palmPattern=HarmonyPalmHapticPattern(config)
    fun applyAnchor(shape:HarmonyHapticShape,active:Boolean):HarmonyHapticShape = if(!active) shape else shape.copy(
        strength=(shape.strength*config.anchorStrengthScale).toFloat(),
        pulseDurationMs=(shape.pulseDurationMs+config.anchorExtraDurationMs).coerceAtMost(config.maxPulseDurationMs),
        tempoOffsetBpm=shape.tempoOffsetBpm+config.anchorTempoOffsetBpm
    )
    fun update(localFingerCount:Int,remoteFingerCount:Int,localSpeed:Double,remoteSpeed:Double,includeRemote:Boolean=true,localRotationSpeed:Double=0.0,remoteRotationSpeed:Double=0.0,palmFeedback:PalmFeedback?=null):HarmonyHapticShape {
        if(palmFeedback!=null&&palmFeedback.mode!=PalmFeedbackMode.FINGER) return palmPattern.create(palmFeedback)
        val localFingers=localFingerCount.coerceIn(0,MAX_HARMONY_TOUCHES)
        if(localFingers==0) return HarmonyHapticShape(0f,0L,0.0)
        val remoteFingers=if(includeRemote) remoteFingerCount.coerceIn(0,MAX_HARMONY_TOUCHES) else 0
        val localMovement=localSpeed.coerceAtLeast(0.0).coerceIn(0.0,config.maxMovementSpeed)/config.maxMovementSpeed
        val remoteMovement=if(includeRemote) remoteSpeed.coerceAtLeast(0.0).coerceIn(0.0,config.maxMovementSpeed)/config.maxMovementSpeed else 0.0
        val localRotation=localRotationSpeed.coerceAtLeast(0.0).coerceIn(0.0,config.maxRotationSpeedRadPerSec)/config.maxRotationSpeedRadPerSec
        val remoteRotation=if(includeRemote) remoteRotationSpeed.coerceAtLeast(0.0).coerceIn(0.0,config.maxRotationSpeedRadPerSec)/config.maxRotationSpeedRadPerSec else 0.0
        val rotationContribution=localRotation*config.rotationStrength+remoteRotation*config.remoteRotationStrength
        val strength=(config.baseStrength+(localFingers-1)*config.extraFingerStrength+remoteFingers*config.remoteFingerStrength+localMovement*config.movementStrength+remoteMovement*config.remoteMovementStrength+rotationContribution).coerceIn(0.0,config.maxStrength).toFloat()
        val duration=(config.basePulseDurationMs+(localFingers-1)*config.extraFingerDurationMs+localMovement*config.movementDurationMs+remoteMovement*config.remoteMovementDurationMs+(localRotation+remoteRotation*.25)*config.rotationPulseDurationMs).toLong().coerceIn(30L,config.maxPulseDurationMs)
        val tempo=(localMovement+remoteMovement*.25).coerceAtMost(1.0)*config.maxTempoOffsetBpm+(localRotation+remoteRotation*.25).coerceAtMost(1.0)*config.rotationTempoOffsetBpm
        return HarmonyHapticShape(strength,duration,tempo)
    }

}

/** Detects turning from each finger's path, independent of its position or rotation direction. */
class HarmonyRotationTracker(
    private val fadeMs:Long=500L,
    private val minStep:Double=0.0015,
    private val minTurnRadians:Double=0.025
) {
    private var previousPoints=emptyMap<Int,Point>()
    private var previousVectors=emptyMap<Int,Pair<Double,Double>>()
    private var previousVectorAt=emptyMap<Int,Long>()
    private var lastRate=0.0
    private var lastTurnAt:Long?=null

    init { require(fadeMs>0L && minStep>0.0 && minTurnRadians>=0.0) }

    fun record(points:Map<Int,Point>,nowMs:Long) {
        if(points.isEmpty()) { reset(); return }
        var fastest=0.0
        val vectors=mutableMapOf<Int,Pair<Double,Double>>()
        val vectorTimes=mutableMapOf<Int,Long>()
        points.forEach { (id,point) ->
            val old=previousPoints[id]
            if(old!=null) {
                val vector=point.x-old.x to point.y-old.y
                if(hypot(vector.first,vector.second)>=minStep) {
                    val oldVector=previousVectors[id]
                    val oldAt=previousVectorAt[id]
                    if(oldVector!=null && oldAt!=null && nowMs>oldAt) {
                        val cross=oldVector.first*vector.second-oldVector.second*vector.first
                        val dot=oldVector.first*vector.first+oldVector.second*vector.second
                        val turn=abs(atan2(cross,dot))
                        if(turn>=minTurnRadians) fastest=max(fastest,turn/((nowMs-oldAt)/1000.0))
                    }
                    vectors[id]=vector; vectorTimes[id]=nowMs
                } else {
                    previousVectors[id]?.let { vectors[id]=it }; previousVectorAt[id]?.let { vectorTimes[id]=it }
                }
            }
        }
        if(fastest>0.0) { lastRate=fastest; lastTurnAt=nowMs }
        previousPoints=points.toMap(); previousVectors=vectors; previousVectorAt=vectorTimes
    }

    fun radiansPerSecond(nowMs:Long):Double {
        val at=lastTurnAt?:return 0.0
        val age=(nowMs-at).coerceAtLeast(0L)
        if(age>=fadeMs) return 0.0
        return lastRate*(1.0-age.toDouble()/fadeMs)
    }

    fun reset() { previousPoints=emptyMap(); previousVectors=emptyMap(); previousVectorAt=emptyMap(); lastRate=0.0; lastTurnAt=null }
}

/** Estimates the fastest active finger movement and lets it fade shortly after motion stops. */
class HarmonyMotionTracker(private val fadeMs:Long=400L) {
    private var previous=emptyMap<Int,Point>()
    private var previousAt:Long?=null
    private var lastSpeed=0.0
    private var lastMotionAt:Long?=null

    init { require(fadeMs>0L) }

    fun record(points:Map<Int,Point>,nowMs:Long) {
        if(points.isEmpty()) { reset(); return }
        val at=previousAt
        if(at!=null&&nowMs>at) {
            val seconds=(nowMs-at)/1000.0
            val speed=points.mapNotNull { (id,point) -> previous[id]?.let { it.distanceTo(point)/seconds } }.maxOrNull() ?: 0.0
            if(speed>=0.025) { lastSpeed=speed; lastMotionAt=nowMs }
        }
        previous=points.toMap(); previousAt=nowMs
    }

    fun speed(nowMs:Long):Double {
        val at=lastMotionAt?:return 0.0
        val age=(nowMs-at).coerceAtLeast(0L)
        if(age>=fadeMs) return 0.0
        return lastSpeed*(1.0-age.toDouble()/fadeMs)
    }

    fun reset() { previous=emptyMap(); previousAt=null; lastSpeed=0.0; lastMotionAt=null }
}
