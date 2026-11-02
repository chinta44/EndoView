package com.chinta44.endoview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** プレビューの上に三分割グリッドと中心十字を描くオーバーレイ。タッチは透過する。 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint().apply {
        color = Color.argb(140, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val centerPaint = Paint().apply {
        color = Color.argb(200, 255, 213, 79)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // 三分割線
        canvas.drawLine(w / 3f, 0f, w / 3f, h, linePaint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, linePaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, linePaint)
        canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, linePaint)

        // 中心十字
        val cx = w / 2f
        val cy = h / 2f
        val arm = 40f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, centerPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, centerPaint)
        canvas.drawCircle(cx, cy, 14f, centerPaint)
    }
}
