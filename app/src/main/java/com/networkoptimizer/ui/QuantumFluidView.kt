package com.networkoptimizer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

class QuantumFluidView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val path = Path()
    private var time = 0f
    private var isActive = false
    private var colorGlow = Color.parseColor("#00E5FF")

    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // Required for BlurMaskFilter
        updatePaints()
    }

    fun setActive(active: Boolean) {
        isActive = active
        colorGlow = if (active) Color.parseColor("#00E5FF") else Color.parseColor("#2A324A")
        updatePaints()
        if (active && animator == null) {
            startAnimation()
        } else if (!active) {
            animator?.cancel()
            animator = null
            time = 0f
            invalidate()
        }
    }

    private fun updatePaints() {
        paintGlow.color = colorGlow
        paintGlow.maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.OUTER)
        
        paintFill.color = colorGlow
        paintFill.alpha = if (isActive) 40 else 10 // Translucent fill
    }

    private fun startAnimation() {
        animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 4000 // 4 seconds per cycle
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                time = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (Math.min(width, height) / 2f) * 0.7f

        path.reset()

        val points = 120
        for (i in 0..points) {
            val angle = (i.toFloat() / points) * Math.PI * 2

            // Fluid Math: OriginOS style organic deformation
            // Calculate a perturbation based on angle and time
            var radiusOffset = 0f
            if (isActive) {
                val wave1 = sin(angle * 3 + time * 2) * 15f
                val wave2 = cos(angle * 5 - time * 1.5f) * 10f
                val wave3 = sin(angle * 2 + time) * 20f
                radiusOffset = (wave1 + wave2 + wave3).toFloat()
            }

            val r = baseRadius + radiusOffset
            val x = cx + r * cos(angle).toFloat()
            val y = cy + r * sin(angle).toFloat()

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()

        canvas.drawPath(path, paintFill)
        canvas.drawPath(path, paintGlow)
        
        // Draw inner geometric ring (Apple style crisp hardware feel)
        val innerRadius = baseRadius * 0.5f + (if(isActive) sin(time * 3).toFloat() * 10f else 0f)
        paintGlow.maskFilter = null // Crisp line
        paintGlow.strokeWidth = 4f
        canvas.drawCircle(cx, cy, innerRadius, paintGlow)
        updatePaints() // reset filter
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
