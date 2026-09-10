package com.dshare.app.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.OvershootInterpolator

/** Plays the "jelly" checkmark entrance: horizontal bounce, then vertical bounce, then reveals the caption. */
object ConnectAnimator {

    fun playJellyCheck(checkView: View, captionView: View, onDone: () -> Unit) {
        checkView.scaleX = 0f
        checkView.scaleY = 0f
        checkView.alpha = 1f
        captionView.alpha = 0f
        captionView.translationY = 24f

        val overshoot = OvershootInterpolator(6f)

        val scaleXUp = ObjectAnimator.ofFloat(checkView, View.SCALE_X, 0f, 1.15f, 1f).apply {
            duration = 160
            interpolator = overshoot
        }
        val scaleYSettle = ObjectAnimator.ofFloat(checkView, View.SCALE_Y, 0f, 0.85f).apply {
            duration = 80
        }
        val scaleYUp = ObjectAnimator.ofFloat(checkView, View.SCALE_Y, 0.85f, 1.15f, 1f).apply {
            duration = 160
            interpolator = overshoot
        }

        val horizontalPhase = AnimatorSet().apply {
            playTogether(scaleXUp, scaleYSettle)
        }

        val captionFade = ObjectAnimator.ofFloat(captionView, View.ALPHA, 0f, 1f).apply { duration = 140 }
        val captionSlide = ObjectAnimator.ofFloat(captionView, View.TRANSLATION_Y, 24f, 0f).apply {
            duration = 160
            interpolator = OvershootInterpolator(1.5f)
        }
        val captionPhase = AnimatorSet().apply { playTogether(captionFade, captionSlide) }

        AnimatorSet().apply {
            playSequentially(horizontalPhase, scaleYUp, captionPhase)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onDone()
                }
            })
            start()
        }
    }

    /** Cross-fades [from] out and [to] in over [duration]ms. */
    fun crossFade(from: View, to: View, duration: Long = 320) {
        to.alpha = 0f
        to.visibility = View.VISIBLE
        to.animate().alpha(1f).setDuration(duration).setListener(null).start()
        from.animate().alpha(0f).setDuration(duration).withEndAction {
            from.visibility = View.GONE
            from.alpha = 1f
        }.start()
    }
}
