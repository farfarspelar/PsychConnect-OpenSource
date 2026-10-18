package com.psychconnect.ui

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Bitmap
import android.widget.ImageView

/** Static, cached menu artwork. Only applies a temporary touch highlight. */
internal class SpriteImageView(
    context: Context,
    bitmap: Bitmap,
    centerCrop: Boolean = false,
    private val centerCropOffsetY: Float = 0f,
) : ImageView(context) {
    private val highlight = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        1.08f,0f,0f,0f,2f,
        0f,1.08f,0f,0f,2f,
        0f,0f,1.08f,0f,2f,
        0f,0f,0f,1f,0f,
    )))

    init {
        setImageBitmap(bitmap)
        scaleType = if (centerCrop) ScaleType.MATRIX else ScaleType.FIT_CENTER
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (scaleType != ScaleType.MATRIX || w == 0 || h == 0) return

        val bitmap = drawable ?: return
        val scale = maxOf(w.toFloat() / bitmap.intrinsicWidth, h.toFloat() / bitmap.intrinsicHeight)
        val dx = (w - bitmap.intrinsicWidth * scale) / 2f
        val dy = (h - bitmap.intrinsicHeight * scale) / 2f + centerCropOffsetY * h
        imageMatrix = android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
    }

    fun setPressedHighlight(pressed: Boolean) {
        colorFilter = if (pressed) highlight else null
        invalidate()
    }
}
