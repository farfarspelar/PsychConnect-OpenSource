package com.psychconnect.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.ViewGroup
import android.view.View
import android.widget.ImageView

/** Draws two tiny, slow glints around menu control borders; it never handles input. */
internal class MenuElectricOverlay(context: Context, private val targets: List<View>) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val ownLocation=IntArray(2)
    private val transform=Matrix()
    private val rect=RectF()
    private val contour=Path()
    private val measure=PathMeasure()
    private val position=FloatArray(2)
    private val tangent=FloatArray(2)
    private val density=resources.displayMetrics.density
    private var now=SystemClock.elapsedRealtime()
    private var disabled=false
    private val artworkTargets=targets.map { target ->
        if(target is ImageView) target
        else (target as? ViewGroup)?.let { group -> (0 until group.childCount).asSequence().map { group.getChildAt(it) }.filterIsInstance<ImageView>().firstOrNull() } ?: target
    }
    init { isClickable=false; isFocusable=false; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun updateAt(time:Long,off:Boolean) { now=time; disabled=off; invalidate() }
    override fun onDraw(canvas:Canvas) {
        if(disabled||width<=0||height<=0) return
        getLocationOnScreen(ownLocation)
        val coreRadius=2.5f*density
        targets.forEachIndexed { index,target ->
            val artwork=artworkTargets[index]
            if(artwork.width<=0||artwork.height<=0||target.visibility!=VISIBLE) return@forEachIndexed
            val inset=3f*density
            var contentWidth=artwork.width.toFloat()
            var contentHeight=artwork.height.toFloat()
            if(artwork is ImageView && artwork.scaleType==ImageView.ScaleType.FIT_CENTER) {
                val drawable=artwork.drawable
                if(drawable!=null&&drawable.intrinsicWidth>0&&drawable.intrinsicHeight>0) {
                    val fit=minOf(artwork.width.toFloat()/drawable.intrinsicWidth,artwork.height.toFloat()/drawable.intrinsicHeight)
                    contentWidth=drawable.intrinsicWidth*fit
                    contentHeight=drawable.intrinsicHeight*fit
                }
            }
            rect.set(
                (artwork.width-contentWidth)*.5f+inset,
                (artwork.height-contentHeight)*.5f+inset,
                (artwork.width+contentWidth)*.5f-inset,
                (artwork.height+contentHeight)*.5f-inset
            )
            transform.reset()
            artwork.transformMatrixToGlobal(transform)
            transform.mapRect(rect)
            rect.offset(-ownLocation[0].toFloat(),-ownLocation[1].toFloat())
            val radius=minOf(rect.height()*.48f,rect.width()*.2f)
            contour.reset(); contour.addRoundRect(rect,radius,radius,Path.Direction.CW)
            measure.setPath(contour,true)
            val length=measure.length
            if(length<=0f) return@forEachIndexed
            for(dot in 0..1) {
                val phase=((now+index*713L+dot*4_600L)%9_200L)/9_200f
                measure.getPosTan(phase*length,position,tangent)
                val tint=if((index+dot)%2==0) 0xFFB39DFF.toInt() else 0xFF70D7DE.toInt()
                val shine=(.58f+.42f*(1f-kotlin.math.abs(phase-.5f)*2f))
                val x=position[0]; val y=position[1]
                paint.color=withAlpha(tint,(48*shine).toInt()); canvas.drawCircle(x,y,coreRadius*2.7f,paint)
                paint.color=withAlpha(tint,(172*shine).toInt()); canvas.drawCircle(x,y,coreRadius,paint)
                paint.color=withAlpha(0xFFFFFFFF.toInt(),(205*shine).toInt()); canvas.drawCircle(x,y,coreRadius*.38f,paint)
            }
        }
    }
    private fun withAlpha(color:Int,alpha:Int)=(color and 0x00FFFFFF) or (alpha.coerceIn(0,255) shl 24)
}
