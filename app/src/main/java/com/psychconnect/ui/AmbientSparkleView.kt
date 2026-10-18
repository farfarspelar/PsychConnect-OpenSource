package com.psychconnect.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/** Fixed-position, softly tinted galactic twinkles over the stationary menu artwork. */
internal class AmbientSparkleView(context: Context) : View(context) {
    private data class Star(val x: Float, val y: Float, val phaseMs: Long, val tint: Int)
    private val stars = listOf(
        Star(.12f,.13f,0,Color.rgb(209,185,255)), Star(.86f,.20f,1_100,Color.rgb(159,224,255)),
        Star(.08f,.43f,2_300,Color.rgb(208,174,255)), Star(.92f,.57f,3_700,Color.rgb(158,224,255)),
        Star(.15f,.84f,2_900,Color.rgb(209,185,255)), Star(.84f,.89f,800,Color.rgb(159,224,255)),
        Star(.30f,.08f,4_800,Color.rgb(238,191,255)), Star(.70f,.35f,5_600,Color.rgb(168,222,255)),
        Star(.23f,.61f,900,Color.rgb(221,181,255)), Star(.76f,.76f,2_000,Color.rgb(149,226,232)),
        Star(.48f,.16f,3_300,Color.rgb(214,202,255)), Star(.57f,.91f,5_100,Color.rgb(173,213,255)),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var disabled = false
    private var reducedMotion = false
    private var reducedEffects = false
    private var timeMs = 0L
    private val density = resources.displayMetrics.density

    fun updateAt(nowMs: Long, animationsDisabled: Boolean, reduceMotion: Boolean, reduceEffects: Boolean) {
        val changed = disabled != animationsDisabled || reducedMotion != reduceMotion || reducedEffects != reduceEffects || timeMs != nowMs
        disabled = animationsDisabled
        reducedMotion = reduceMotion
        reducedEffects = reduceEffects
        timeMs = nowMs
        if (changed) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (disabled || reducedMotion || width <= 0 || height <= 0) return
        val period = if (reducedEffects) 11_000L else 8_500L
        val maxAlpha = if (reducedEffects) 14 else 29
        val minAlpha = 0
        stars.forEach { star ->
            val alpha = SparkleMotion.alphaAt(timeMs, star.phaseMs, period, minAlpha, maxAlpha)
            val x = star.x * width
            val y = star.y * height
            val radius = density * if (reducedEffects) .9f else 1.15f
            paint.color = Color.argb(alpha / 4, Color.red(star.tint), Color.green(star.tint), Color.blue(star.tint))
            canvas.drawCircle(x, y, radius * 5f, paint)
            paint.color = Color.argb(alpha, Color.red(star.tint), Color.green(star.tint), Color.blue(star.tint))
            canvas.drawCircle(x, y, radius, paint)
        }
    }
}

/** Pure, deterministic opacity curve for slow, fixed-position star twinkles. */
internal object SparkleMotion {
    fun alphaAt(nowMs: Long, phaseMs: Long, periodMs: Long, minAlpha: Int, maxAlpha: Int): Int {
        val period = periodMs.coerceAtLeast(1L)
        val phase = Math.floorMod(nowMs + phaseMs, period).toDouble() / period
        val fade = (1.0 - cos(2.0 * PI * phase)) * .5
        return (minAlpha + fade * (maxAlpha - minAlpha)).roundToInt().coerceIn(0,255)
    }
}
