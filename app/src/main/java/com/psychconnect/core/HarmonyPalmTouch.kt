package com.psychconnect.core

import kotlin.math.min

/** Raw, device-specific MotionEvent contact values. Values are never sent to the server. */
data class PalmTouchMeasurement(
    val size:Float?,
    val touchMajor:Float?,
    val touchMinor:Float?,
    val pressure:Float?
)

data class PalmTouchObservation(val id:Int,val point:Point,val measurement:PalmTouchMeasurement)

data class PalmTouchFeatures(val confidence:Double,val usable:Boolean)

/** Extracts conservative normalized breadth evidence. Pressure is intentionally diagnostic only. */
object PalmTouchFeatureExtractor {
    fun extract(measurement:PalmTouchMeasurement,screenShortDimensionPx:Int):PalmTouchFeatures {
        if(screenShortDimensionPx<=0) return PalmTouchFeatures(0.0,false)
        val scores=mutableListOf<Double>()
        measurement.size?.takeIf { it.isFinite() && it in 0.0f..1.0f && it>0f }?.let {
            scores += ramp(it.toDouble(),.18,.50)
        }
        measurement.touchMajor?.takeIf { it.isFinite() && it>0f && it<=screenShortDimensionPx*.45f }?.let {
            scores += ramp((it/screenShortDimensionPx).toDouble(),.055,.14)
        }
        measurement.touchMinor?.takeIf { it.isFinite() && it>0f && it<=screenShortDimensionPx*.30f }?.let {
            scores += ramp((it/screenShortDimensionPx).toDouble(),.03,.09)
        }
        if(scores.size<2) return PalmTouchFeatures(0.0,false)
        return PalmTouchFeatures(scores.average(),true)
    }

    private fun ramp(value:Double,start:Double,end:Double):Double = ((value-start)/(end-start)).coerceIn(0.0,1.0)
}

enum class PalmContactClass { FINGER, CANDIDATE, PALM }
data class PalmContactAssessment(val classification:PalmContactClass,val confidence:Double,val sensorDataAvailable:Boolean)
data class PalmTouchSnapshot(val palmActive:Boolean,val confidence:Double,val sensorDataAvailable:Boolean,val contacts:Map<Int,PalmContactAssessment>)

/** Per-pointer duration, stability, confidence threshold and hysteresis. Never infers palms from finger count. */
class PalmContactStateMachine(
    private val minContactMs:Long=900L,
    private val minStableMs:Long=650L,
    private val activationMs:Long=250L,
    private val releaseMs:Long=250L,
    private val activationConfidence:Double=.84,
    private val releaseConfidence:Double=.48,
    private val stableMovementLimit:Double=.009
) {
    private data class State(var startedAt:Long,var stableSince:Long,var lastPoint:Point,var candidateSince:Long?=null,var belowSince:Long?=null,var active:Boolean=false)
    private val states=mutableMapOf<Int,State>()

    init {
        require(minContactMs>0L && minStableMs>0L && activationMs>0L && releaseMs>0L)
        require(activationConfidence in 0.0..1.0 && releaseConfidence in 0.0..activationConfidence)
        require(stableMovementLimit>0.0)
    }

    fun update(observations:List<PalmTouchObservation>,screenShortDimensionPx:Int,nowMs:Long):PalmTouchSnapshot {
        val byId=observations.distinctBy { it.id }.associateBy { it.id }
        states.keys.retainAll(byId.keys)
        val results=linkedMapOf<Int,PalmContactAssessment>()
        byId.forEach { (id,observation) ->
            val state=states.getOrPut(id) { State(nowMs,nowMs,observation.point) }
            if(state.lastPoint.distanceTo(observation.point)>stableMovementLimit) state.stableSince=nowMs
            state.lastPoint=observation.point
            val features=PalmTouchFeatureExtractor.extract(observation.measurement,screenShortDimensionPx)
            val contactProgress=(nowMs-state.startedAt).coerceAtLeast(0L).toDouble()/minContactMs
            val stableProgress=(nowMs-state.stableSince).coerceAtLeast(0L).toDouble()/minStableMs
            val confidence=if(features.usable) features.confidence*min(1.0,contactProgress)*min(1.0,stableProgress) else 0.0
            if(confidence>=activationConfidence) {
                state.belowSince=null
                if(state.candidateSince==null) state.candidateSince=nowMs
                if(nowMs-(state.candidateSince?:nowMs)>=activationMs) state.active=true
            } else {
                state.candidateSince=null
                if(confidence<releaseConfidence) {
                    if(state.belowSince==null) state.belowSince=nowMs
                    if(state.active&&nowMs-(state.belowSince?:nowMs)>=releaseMs) state.active=false
                } else state.belowSince=null
            }
            val classification=when { state.active->PalmContactClass.PALM; state.candidateSince!=null->PalmContactClass.CANDIDATE; else->PalmContactClass.FINGER }
            results[id]=PalmContactAssessment(classification,confidence,features.usable)
        }
        return PalmTouchSnapshot(results.values.any { it.classification==PalmContactClass.PALM },results.values.maxOfOrNull { it.confidence }?:0.0,results.values.any { it.sensorDataAvailable },results)
    }

    fun reset()=states.clear()
}

enum class PalmFeedbackMode { FINGER, LOCAL_PALM, REMOTE_PALM, SHARED_PALM }
data class PalmFeedback(val mode:PalmFeedbackMode,val sharedBlend:Double)

/** Smoothly eases into the shared hand-contact rhythm and fades it on palm removal or consent loss. */
class HarmonyPalmSynchronizer(private val transitionMs:Long=900L) {
    private var blend=0.0
    private var lastAt:Long?=null
    init { require(transitionMs>0L) }

    fun update(localPalm:Boolean,remotePalm:Boolean,sharingAllowed:Boolean,nowMs:Long):PalmFeedback {
        val at=lastAt
        if(!sharingAllowed) blend=0.0
        else if(at!=null) {
            val step=((nowMs-at).coerceAtLeast(0L).toDouble()/transitionMs).coerceAtMost(1.0)
            blend=if(localPalm&&remotePalm) (blend+step).coerceAtMost(1.0) else (blend-step).coerceAtLeast(0.0)
        }
        lastAt=nowMs
        val mode=when {
            blend>0.0->PalmFeedbackMode.SHARED_PALM
            localPalm->PalmFeedbackMode.LOCAL_PALM
            sharingAllowed&&remotePalm->PalmFeedbackMode.REMOTE_PALM
            else->PalmFeedbackMode.FINGER
        }
        return PalmFeedback(mode,blend)
    }

    fun reset() { blend=0.0; lastAt=null }
}
