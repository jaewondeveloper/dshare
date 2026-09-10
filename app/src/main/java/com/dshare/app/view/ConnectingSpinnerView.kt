package com.dshare.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import com.dshare.app.R
import kotlin.math.sin

/**
 * Self-drawn indeterminate spinner (no system ProgressBar/CircularProgressIndicator
 * involved): a rounded arc that continuously rotates while its sweep length breathes
 * in and out, driven by a Choreographer frame loop for consistent, jank-free motion.
 */
class ConnectingSpinnerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val oval = RectF()

    private var elapsedMs = 0f
    private var lastFrameNanos = 0L
    private var running = false

    private val cycleMs = 1400f
    private val minSweepDeg = 14f
    private val maxSweepDeg = 300f
    private val rotationDegPerMs = 360f / 1000f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNanos != 0L) {
                val dtMs = (frameTimeNanos - lastFrameNanos) / 1_000_000f
                elapsedMs += dtMs.coerceAtMost(50f)
                invalidate()
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val stroke = minOf(w, h) * 0.1f
        paint.strokeWidth = stroke
        val inset = stroke / 2f + 1f
        oval.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        val phase = (elapsedMs % cycleMs) / cycleMs
        val breathe = sin((phase * Math.PI).toFloat().toDouble()).toFloat()
        val sweep = minSweepDeg + (maxSweepDeg - minSweepDeg) * breathe
        val baseRotation = (elapsedMs * rotationDegPerMs) % 360f
        canvas.drawArc(oval, baseRotation - 90f, sweep, false, paint)
    }
}
