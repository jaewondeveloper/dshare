package com.dshare.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.max
import kotlin.random.Random

/**
 * Space-themed background: small points drift slowly toward the viewer (perspective
 * starfield). Speed can be smoothly ramped up (warp effect) while a connection is in
 * progress, and eased back down afterwards.
 */
class StarfieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Star(var x: Float, var y: Float, var z: Float, var pz: Float)

    private val stars = mutableListOf<Star>()
    private val starCount = 220
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
    }

    private var baseSpeed = 60f
    private var currentSpeedMultiplier = IDLE_MULTIPLIER
    private var speedAnimator: ValueAnimator? = null

    companion object {
        /** Slow ambient drift while waiting for a connection. */
        const val IDLE_MULTIPLIER = 0.12f

        /** Calm-but-lively pace once a viewer is connected/streaming. */
        const val CONNECTED_MULTIPLIER = 1f

        /** Hyperspace burst while a connection is being negotiated. */
        const val WARP_MULTIPLIER = 14f
    }

    private var lastFrameNanos = 0L
    private var running = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNanos != 0L) {
                val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.05f)
                update(dt)
                invalidate()
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun ensureStars() {
        if (stars.isNotEmpty() || width == 0 || height == 0) return
        repeat(starCount) { stars.add(randomStar(true)) }
    }

    private fun randomStar(initial: Boolean): Star {
        val w = max(width, 1)
        val h = max(height, 1)
        val x = Random.nextFloat() * w - w / 2f
        val y = Random.nextFloat() * h - h / 2f
        val z = if (initial) Random.nextFloat() * w else w.toFloat()
        return Star(x, y, z, z)
    }

    private fun update(dt: Float) {
        if (width == 0 || height == 0) return
        ensureStars()
        val speed = baseSpeed * currentSpeedMultiplier
        for (i in stars.indices) {
            val s = stars[i]
            s.pz = s.z
            s.z -= speed * dt * 30f
            if (s.z <= 1f) {
                val fresh = randomStar(false)
                s.x = fresh.x
                s.y = fresh.y
                s.z = width.toFloat()
                s.pz = s.z
            }
        }
    }

    /** Smoothly ramps the drift speed toward [multiplier] (1 = calm drift, >1 = warp). */
    fun setWarpMultiplier(multiplier: Float, durationMs: Long = 700) {
        speedAnimator?.cancel()
        speedAnimator = ValueAnimator.ofFloat(currentSpeedMultiplier, multiplier).apply {
            duration = durationMs
            addUpdateListener { currentSpeedMultiplier = it.animatedValue as Float }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        speedAnimator?.cancel()
    }

    /** Stops the per-frame redraw loop. Call while fully hidden behind the video so it
     *  doesn't compete with the decoder/renderer for CPU/GPU and cause playback stutter. */
    fun pauseAnimation() {
        running = false
    }

    fun resumeAnimation() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        ensureStars()
        val cx = w / 2f
        val cy = h / 2f

        for (s in stars) {
            val sx = cx + (s.x / s.z) * cx
            val sy = cy + (s.y / s.z) * cy
            val px = cx + (s.x / s.pz) * cx
            val py = cy + (s.y / s.pz) * cy

            val depthFactor = (1f - s.z / w).coerceIn(0f, 1f)
            val alpha = (60 + depthFactor * 195).toInt().coerceIn(0, 255)
            val radius = 0.6f + depthFactor * 2.4f

            if (currentSpeedMultiplier > 1.6f) {
                linePaint.alpha = alpha
                linePaint.strokeWidth = radius
                canvas.drawLine(px, py, sx, sy, linePaint)
            } else {
                paint.alpha = alpha
                canvas.drawCircle(sx, sy, radius, paint)
            }
        }
    }
}
