package com.psychconnect.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToLong

/** Single scheduler owns all motor output; changing BPM adjusts the next interval without overlapping effects. */
class HapticEngine(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else legacyVibrator(context)
    private val handler = Handler(Looper.getMainLooper())
    var enabled = true
    /** Called on the main thread whenever a heartbeat cycle starts, including when motor output is disabled. */
    var onBeat: ((Float) -> Unit)? = null
    var intensity = 0.45f
        set(value) { field = value.coerceIn(0.1f, 1f) }
    private var active = false
    private var bpm = 65.0
    private var lastSync = false
    private var harmonyStrength=1.0f
    private var pulseDurationMs=42L
    private var nextPulse = Runnable { pulse() }
    val hasVibrator get() = vibrator.hasVibrator()
    val hasAmplitudeControl get() = Build.VERSION.SDK_INT >= 26 && vibrator.hasAmplitudeControl()

    fun connect(delayInitialPulse:Boolean=false) { stop(); active = true; bpm = 65.0; lastSync = true; if(delayInitialPulse) handler.postDelayed(nextPulse,900) else { beat(true); handler.postDelayed(nextPulse, 900) } }
    fun update(state: Heartbeat) {
        if (!active) return
        bpm = state.bpm.coerceIn(50.0, 160.0)
        if (state.synchronized && !lastSync) beat(true, 1.35f)
        lastSync = state.synchronized
    }
    fun setHarmonyShape(shape:HarmonyHapticShape) {
        harmonyStrength=shape.strength.coerceIn(0.0f,1.8f)
        pulseDurationMs=shape.pulseDurationMs.coerceIn(30L,78L)
    }
    fun resetHarmonyShape() { harmonyStrength=1.0f; pulseDurationMs=42L }
    /** A contact knock; a second finger adds a short paired knock. */
    fun harmonyTap(fingers:Int=1) {
        if(!enabled||!hasVibrator) return
        val amplitude=(intensity*(if(fingers>=2) 185 else 105)).toInt().coerceIn(1,255)
        if(Build.VERSION.SDK_INT>=26) {
            val timings=if(fingers>=2) longArrayOf(0,24,46,38) else longArrayOf(0,22)
            val amplitudes=if(fingers>=2) intArrayOf(0,amplitude,0,(amplitude*.72).toInt()) else intArrayOf(0,amplitude)
            vibrator.vibrate(VibrationEffect.createWaveform(timings,amplitudes,-1))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(if(fingers>=2) longArrayOf(0,24,46,38) else longArrayOf(0,22),-1)
        }
    }
    private fun pulse() { if (!active) return; beat(true); handler.postDelayed(nextPulse, (60_000.0 / bpm).roundToLong()) }
    private fun beat(double: Boolean, boost: Float = 1.0f) {
        onBeat?.invoke(boost)
        if (!enabled || !hasVibrator) return
        val amp = (intensity * 125 * harmonyStrength).toInt().coerceIn(1, 255)
        if (Build.VERSION.SDK_INT >= 26) {
            // A compact lub-dub pattern leaves enough quiet space even at the fastest BPM.
            val duration=pulseDurationMs
            val timings = if (double) longArrayOf(0, duration, 78, (duration*.72).toLong(), 0) else longArrayOf(0, duration)
            val strong=(amp*boost).toInt().coerceIn(1,255)
            val amps = if (double) intArrayOf(0, strong, 0, (strong * 0.65).toInt(), 0) else intArrayOf(0, strong)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(if (double) longArrayOf(0, 38, 95, 52) else longArrayOf(0, 42), -1)
        }
    }
    fun stop() { active = false; lastSync = false; handler.removeCallbacks(nextPulse); vibrator.cancel() }
    fun test() { stop(); active = true; beat(true); handler.postDelayed({ stop() }, 500) }

    /** A brief, restrained three-tap cue for a round ability. It leaves the heartbeat scheduler running. */
    fun abilityPulse() {
        if (!enabled || !hasVibrator) return
        val amp=(intensity*105).toInt().coerceIn(1,255)
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(
                longArrayOf(0,28,72,28,72,28), intArrayOf(0,amp,0,amp,0,amp), -1
            ))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(longArrayOf(0,28,72,28,72,28), -1)
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        private fun legacyVibrator(context: Context): Vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
}
