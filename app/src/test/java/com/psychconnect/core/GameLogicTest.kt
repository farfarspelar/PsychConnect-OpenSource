package com.psychconnect.core

import org.junit.Assert.*
import org.junit.Test

class GameLogicTest {
    private fun likelyPalm()=PalmTouchMeasurement(size=.8f,touchMajor=180f,touchMinor=120f,pressure=.5f)
    @Test fun palmDetectionNeedsLargeUsableStableLongContact() {
        val machine=PalmContactStateMachine()
        val sample=PalmTouchObservation(1,Point(.5,.5),likelyPalm())
        assertFalse(machine.update(listOf(sample),1000,0).palmActive)
        assertFalse(machine.update(listOf(sample),1000,900).palmActive)
        assertTrue(machine.update(listOf(sample),1000,1150).palmActive)
        assertFalse(machine.update(emptyList(),1000,1200).palmActive)
    }
    @Test fun smallTwoAndFiveFingerContactsNeverBecomePalmsFromCountAlone() {
        val machine=PalmContactStateMachine()
        for(count in listOf(2,5)) {
            val fingers=(0 until count).map { PalmTouchObservation(it,Point(.1+it*.1,.5),PalmTouchMeasurement(.08f,50f,35f,.4f)) }
            assertFalse(machine.update(fingers,1000,0).palmActive)
            assertFalse(machine.update(fingers,1000,5000).palmActive)
        }
    }
    @Test fun ambiguousAndMissingPalmMeasurementsStayFingerInput() {
        val machine=PalmContactStateMachine()
        val ambiguous=PalmTouchObservation(1,Point(.5,.5),PalmTouchMeasurement(.25f,80f,null,.8f))
        val noData=PalmTouchObservation(2,Point(.6,.5),PalmTouchMeasurement(null,null,null,null))
        val result=machine.update(listOf(ambiguous,noData),1000,0)
        assertFalse(result.palmActive)
        assertTrue(result.contacts.values.none { it.classification==PalmContactClass.PALM })
        assertFalse(result.contacts.getValue(2).sensorDataAvailable)
        val noisy=PalmTouchFeatureExtractor.extract(PalmTouchMeasurement(Float.NaN,Float.POSITIVE_INFINITY,Float.NaN,Float.NaN),1000)
        assertFalse(noisy.usable)
    }
    @Test fun palmRecognitionUsesReleaseHysteresisAndThenReturnsToFinger() {
        val machine=PalmContactStateMachine()
        machine.update(listOf(PalmTouchObservation(1,Point(.5,.5),likelyPalm())),1000,0)
        machine.update(listOf(PalmTouchObservation(1,Point(.5,.5),likelyPalm())),1000,900)
        assertTrue(machine.update(listOf(PalmTouchObservation(1,Point(.5,.5),likelyPalm())),1000,1150).palmActive)
        val midConfidence=PalmTouchMeasurement(size=.35f,touchMajor=100f,touchMinor=60f,pressure=.5f)
        assertTrue(machine.update(listOf(PalmTouchObservation(1,Point(.5,.5),midConfidence)),1000,1200).palmActive)
        val small=PalmTouchMeasurement(size=.08f,touchMajor=50f,touchMinor=35f,pressure=.5f)
        assertTrue("brief sensor noise uses release hysteresis",machine.update(listOf(PalmTouchObservation(1,Point(.5,.5),small)),1000,1250).palmActive)
        assertFalse(machine.update(listOf(PalmTouchObservation(1,Point(.5,.5),small)),1000,1500).palmActive)
    }
    @Test fun sharedPalmHeartbeatRequiresConsentAndFadesOnRemoval() {
        val sync=HarmonyPalmSynchronizer(transitionMs=900)
        assertEquals(PalmFeedbackMode.LOCAL_PALM,sync.update(true,true,false,0).mode)
        assertEquals(0.0,sync.update(true,true,false,500).sharedBlend,1e-9)
        val beginning=sync.update(true,true,true,600)
        assertEquals(PalmFeedbackMode.SHARED_PALM,beginning.mode)
        assertTrue(beginning.sharedBlend in 0.0..1.0)
        val established=sync.update(true,true,true,1500)
        assertEquals(1.0,established.sharedBlend,1e-9)
        val fading=sync.update(false,true,true,1800)
        assertTrue(fading.sharedBlend<established.sharedBlend)
        assertTrue(fading.sharedBlend>0.0)
        val revoked=sync.update(true,true,false,1900)
        assertEquals(PalmFeedbackMode.LOCAL_PALM,revoked.mode)
        assertEquals(0.0,revoked.sharedBlend,1e-9)
    }
    @Test fun palmPatternUsesGentleShortPulsesAndSharedBlend() {
        val pattern=HarmonyPalmHapticPattern()
        val local=pattern.create(PalmFeedback(PalmFeedbackMode.LOCAL_PALM,0.0))
        val remote=pattern.create(PalmFeedback(PalmFeedbackMode.REMOTE_PALM,0.0))
        val shared=pattern.create(PalmFeedback(PalmFeedbackMode.SHARED_PALM,.5))
        assertEquals(PalmFeedbackMode.LOCAL_PALM,local.palmMode)
        assertEquals(30L,local.pulseDurationMs)
        assertTrue(remote.strength<local.strength)
        assertTrue(shared.strength>local.strength)
        assertTrue(shared.tempoOffsetBpm>0.0)
    }
    @Test fun normalizesAgainstBothDimensionsAndClamps() {
        assertEquals(Point(.5,.5),Point.normalize(500f,1000f,1000,2000))
        assertEquals(Point(1.0,0.0),Point.normalize(1200f,-10f,1000,2000))
        assertEquals(Point(.5,.5),Point.normalize(500f,500f,1000,1000))
    }
    @Test fun harmonyTouchSnapshotUsesReportedTouchesUpToTheSharedFiveFingerLimit() {
        val two=(0..1).map { HarmonyTouch(it,Point(it.toDouble(),.5)) }
        assertEquals(two,harmonyTouchSnapshot(two))
        val six=(0..5).map { HarmonyTouch(it,Point(it.toDouble(),.5)) }
        assertEquals(5,harmonyTouchSnapshot(six).size)
        assertEquals((0..4).toList(),harmonyTouchSnapshot(six).map { it.id })
        assertEquals(5,MAX_HARMONY_TOUCHES)
    }
    @Test fun harmonyPromotesAnAlreadyDownFingerWhenATrackedFingerLifts() {
        assertEquals(listOf(0,1,3,4,5),harmonyTouchIdsAfterPointerUp((0..4).toList(),(0..5).toList(),2))
        assertEquals(listOf(1),harmonyTouchIdsAfterPointerUp(listOf(0),listOf(0,1),0))
    }
    @Test fun harmonyHapticsGrowWithFingerCountAndMovement() {
        val mapper=HarmonyHapticMapper()
        val one=mapper.update(1,1,0.0,0.0)
        val two=mapper.update(2,1,0.0,0.0)
        val moving=mapper.update(1,1,.7,.2)
        assertTrue(two.strength>one.strength)
        assertTrue(two.pulseDurationMs>one.pulseDurationMs)
        assertTrue(moving.strength>one.strength)
        assertTrue(moving.pulseDurationMs>one.pulseDurationMs)
        assertTrue(moving.tempoOffsetBpm>0.0)
    }
    @Test fun harmonyAnchorSoftensAndSlowsWithoutChangingNormalTouchMapping() {
        val mapper=HarmonyHapticMapper()
        val normal=mapper.update(1,0,0.0,0.0)
        val anchor=mapper.applyAnchor(normal,true)
        assertTrue(anchor.strength<normal.strength)
        assertTrue(anchor.pulseDurationMs>normal.pulseDurationMs)
        assertTrue(anchor.tempoOffsetBpm<normal.tempoOffsetBpm)
        assertEquals(normal,mapper.applyAnchor(normal,false))
        assertTrue(mapper.update(1,0,.5,0.0).strength>0f)
    }
    @Test fun harmonyRemoteTouchInfluenceRequiresExplicitSharedHaptics() {
        val mapper=HarmonyHapticMapper()
        val ownTouch=mapper.update(1,0,0.0,0.0,includeRemote=false)
        val denied=mapper.update(1,5,.0,.7,includeRemote=false)
        val allowed=mapper.update(1,5,0.0,.7,includeRemote=true)
        assertEquals(ownTouch,denied)
        assertTrue(allowed.strength>ownTouch.strength)
        assertTrue(allowed.pulseDurationMs>ownTouch.pulseDurationMs)
    }
    @Test fun harmonyCirclesAddStrengthAndRotationRateFadesWhenMotionStops() {
        val tracker=HarmonyRotationTracker()
        for(step in 0..12) {
            val angle=step*Math.PI/6.0
            tracker.record(mapOf(0 to Point(.5+.25*kotlin.math.cos(angle),.5+.25*kotlin.math.sin(angle))),step*100L)
        }
        val rotation=tracker.radiansPerSecond(1200)
        assertTrue("circle path should register turning",rotation>2.0)
        assertEquals(0.0,tracker.radiansPerSecond(1700),1e-9)
        val mapper=HarmonyHapticMapper()
        val still=mapper.update(1,0,0.0,0.0,includeRemote=false)
        val circling=mapper.update(1,0,0.0,0.0,includeRemote=false,localRotationSpeed=rotation)
        assertTrue(circling.strength>still.strength)
        assertTrue(circling.pulseDurationMs>still.pulseDurationMs)
        assertTrue(circling.tempoOffsetBpm>still.tempoOffsetBpm)
    }
    @Test fun harmonyStraightSwipeDoesNotCountAsRotation() {
        val tracker=HarmonyRotationTracker()
        for(step in 0..10) tracker.record(mapOf(0 to Point(.2+step*.03,.4)),step*50L)
        assertEquals(0.0,tracker.radiansPerSecond(500),1e-9)
    }
    @Test fun harmonyMotionFadesAfterTheFingerStops() {
        val tracker=HarmonyMotionTracker()
        tracker.record(mapOf(0 to Point(0.0,0.0)),0)
        tracker.record(mapOf(0 to Point(.1,0.0)),200)
        assertEquals(.5,tracker.speed(200),1e-9)
        assertEquals(.375,tracker.speed(300),1e-9)
        assertEquals(.25,tracker.speed(400),1e-9)
        assertEquals(0.0,tracker.speed(600),1e-9)
    }
    @Test fun distanceIsEuclideanAndBounded() {
        assertEquals(.5,Point(0.0,0.0).distanceTo(Point(.3,.4)),1e-9)
        assertEquals(1.41421356237,Point(0.0,0.0).distanceTo(Point(1.0,1.0)),1e-9)
    }
    @Test fun heartbeatIsSmoothAndReturnsToRest() {
        val mapper=HeartbeatMapper(); val near=mapper.update(0.0); val far=mapper.update(.65); val closer=mapper.update(0.0)
        assertEquals(65.0,near.bpm,1e-9); assertTrue(far.bpm>near.bpm); assertTrue(closer.bpm<far.bpm)
        assertTrue(mapper.update(.035).synchronized); assertFalse(mapper.update(.1).synchronized)
    }
    @Test fun heartbeatCanGetFasterAsPlayersGetCloser() {
        val mapper=HeartbeatMapper(HeartbeatConfig(smoothing=1.0,fasterWhenClose=true))
        val close=mapper.update(0.0)
        val far=mapper.update(.65)
        assertEquals(150.0,close.bpm,1e-9)
        assertEquals(65.0,far.bpm,1e-9)
    }
    @Test fun touchRejectsOtherPointerAndIgnoresLateMoves() {
        val tracker=TouchTracker(); assertTrue(tracker.down(1,Point(.1,.2),10)); assertFalse(tracker.down(2,Point(.5,.5),11))
        tracker.move(2,Point(.9,.9),12); assertEquals(Point(.1,.2),tracker.state.point)
        tracker.move(1,Point(.4,.4),13); assertEquals(Point(.4,.4),tracker.state.point)
        tracker.up(2,14); assertTrue(tracker.state.active); tracker.cancel(15); assertFalse(tracker.state.active)
    }
    @Test fun speedLimiterAllowsNaturalMotionAndCapsFastJumps() {
        val limiter=SpeedLimiter(.5); assertEquals(Point(0.0,0.0),limiter.update(Point(0.0,0.0),0).point)
        assertFalse(limiter.update(Point(.01,0.0),100).limited)
        val capped=limiter.update(Point(1.0,1.0),200)
        assertTrue(capped.limited); assertEquals(.05,capped.point.distanceTo(Point(.01,0.0)),.001)
    }
    @Test fun ownServerEchoCannotReplaceOpponentPosition() {
        val remote=RemoteTouchTracker()
        assertTrue(remote.update("phone-a","phone-b",true,Point(.8,.7),10))
        assertFalse(remote.update("phone-a","phone-a",true,Point(.1,.2)))
        assertFalse(remote.update("phone-a","phone-b",true,Point(.1,.2),9))
        assertFalse(remote.update("phone-a","phone-b",true,Point(.1,.2),10))
        assertEquals(Point(.8,.7),remote.point); assertTrue(remote.active)
        remote.update("phone-a","phone-b",false,null,11); assertFalse(remote.active)
    }
    @Test fun networkDiagnosticsEstimateRttJitterAndPacketAge() {
        val metrics=NetworkDiagnostics()
        assertTrue(metrics.recordPong(1000,50100.0,1040))
        assertEquals(40L,metrics.snapshot().rttMs)
        assertEquals(20L,metrics.snapshot(50110.0,1050).updateAgeMs)
        assertTrue(metrics.recordPong(2000,51100.0,2050))
        assertEquals(1L,metrics.snapshot().jitterMs)
        assertFalse(metrics.recordPong(2000,51100.0,1999))
    }
    @Test fun remotePositionBufferInterpolatesAndRejectsOutOfOrderTimes() {
        val buffer=RemotePositionBuffer(capacity=3)
        assertTrue(buffer.add(100.0,Point(0.0,.2)))
        assertTrue(buffer.add(200.0,Point(.8,.6)))
        assertEquals(Point(.4,.4),buffer.sampleAt(150.0))
        assertEquals(Point(0.0,.2),buffer.sampleAt(50.0))
        assertEquals(Point(.8,.6),buffer.sampleAt(250.0))
        assertFalse(buffer.add(199.0,Point(.3,.3)))
        assertNull(buffer.sampleAt(Double.NaN))
    }
    @Test fun scoreRewardsSustainedAlignmentAndHandlesEmptyRounds() {
        val good=ScoringEngine.score((0..20).map { Sample(it*100L,.02) },0,2000)
        val poor=ScoringEngine.score((0..20).map { Sample(it*100L,.7) },0,2000)
        assertTrue(good.score>poor.score); assertEquals(100,good.alignmentPercent); assertEquals(0,poor.alignmentPercent)
        assertEquals(0,ScoringEngine.score(emptyList(),0,1000).score)
        assertEquals(0,ScoringEngine.score(listOf(Sample(0,.0)),10,10).score)
        assertEquals(.05,ScoringEngine.score(listOf(Sample(0,.0)),0,1000).synchronizedSeconds,1e-9)
    }
    @Test fun scoreRewardsNearnessAndTracksLongestContinuousSync() {
        val samples=listOf(Sample(0,.03),Sample(100,.02),Sample(200,.2),Sample(300,.01),Sample(400,.02))
        val result=ScoringEngine.score(samples,0,500)
        assertTrue(result.score>0)
        assertEquals(80,result.alignmentPercent)
        assertEquals(.2,result.longestSyncSeconds,1e-9)
        assertTrue(ScoringEngine.score(samples,0,500).score>ScoringEngine.score(samples.map { it.copy(distance=.5) },0,500).score)
    }
    @Test fun sharedConnectionScoreNeedsBothPlayersMovingAndDecaysAtRest() {
        val moving=SharedConnectionScoring.step(20.0,.01,true,true,100)
        val resting=SharedConnectionScoring.step(20.0,.01,true,false,100)
        assertTrue(moving>20.0); assertTrue(resting<20.0)
        assertEquals(0.0,SharedConnectionScoring.step(.5,.8,false,false,500),0.0)
        assertEquals(100.0,SharedConnectionScoring.step(99.9,0.0,true,true,250),0.0)
    }
    @Test fun roundStateTransitionsAreGuarded() {
        val game=RoundStateMachine(); game.start(); assertEquals(RoundPhase.WAITING,game.phase)
        game.playersReady(); game.start(); assertEquals(RoundPhase.ACTIVE,game.phase)
        game.finish(); assertEquals(RoundPhase.RESULT,game.phase); game.nextRound(); assertEquals(2,game.round)
        assertEquals(RoundPhase.READY,game.phase); game.reset(); assertEquals(1,game.round)
    }
}
