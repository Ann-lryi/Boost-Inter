package com.networkoptimizer.ui

import android.view.animation.Interpolator
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

class SpringInterpolator(private val factor: Float = 0.4f) : Interpolator {
    override fun getInterpolation(input: Float): Float {
        // Fix: Explicitly cast everything to Float to match kotlin.math.pow(Float, Float)
        val power = 2.0f.pow(-10.0f * input)
        val term = sin((input - factor / 4f) * (2f * Math.PI.toFloat()) / factor)
        return (power * term + 1f)
    }
}