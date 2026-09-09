package com.dshare.app.util

import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Smoothly shrinks the view on press and springs it back on release, per DShare's UI spec. */
fun View.applyPressScale(pressedScale: Float = 0.94f) {
    setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().cancel()
                v.animate()
                    .scaleX(pressedScale)
                    .scaleY(pressedScale)
                    .setDuration(120)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().cancel()
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.5f))
                    .start()
            }
        }
        false
    }
}
