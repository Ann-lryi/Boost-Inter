package com.networkoptimizer.ui

import android.view.animation.Interpolator
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

class SpringInterpolator(private val factor: Float = 0.4f) : Interpolator {
    override fun getInterpolation(input: Float): Float {
        return (pow(2.0, -10.0 * input) * sin((input - factor / 4) * (2 * Math.PI) / factor) + 1).toFloat()
    }
}