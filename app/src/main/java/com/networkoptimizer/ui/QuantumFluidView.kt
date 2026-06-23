package com.networkoptimizer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

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
    
    // Paint cho hiệu ứng hạt gia tốc tốc độ cao
    private val paintSpeed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FFFFFF")
    }

    private var animator: ValueAnimator? = null

    // Cấu trúc Hạt Gia Tốc (Warp Speed Particle System)
    private class Particle(
        var angle: Float, 
        var radius: Float, 
        var speed: Float, 
        var length: Float, 
        var alpha: Int
    )
    private val particles = Array(60) { createParticle(true) }

    private fun createParticle(randomizeRadius: Boolean = false): Particle {
        return Particle(
            angle = Random.nextFloat() * (Math.PI * 2).toFloat(),
            radius = if (randomizeRadius) Random.nextFloat() * 200f else 0f,
            speed = Random.nextFloat() * 18f + 12f,
            length = Random.nextFloat() * 35f + 15f,
            alpha = Random.nextInt(150, 255)
        )
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) 
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
        paintFill.alpha = if (isActive) 40 else 10 
    }

    private fun startAnimation() {
        animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 1500 // Tăng tốc độ chu kỳ Fluid khi bật
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
            var radiusOffset = 0f
            if (isActive) {
                val wave1 = sin(angle * 3 + time * 3) * 15f
                val wave2 = cos(angle * 5 - time * 2) * 12f
                val wave3 = sin(angle * 2 + time * 4) * 20f
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
        
        val innerRadius = baseRadius * 0.5f + (if(isActive) sin(time * 6).toFloat() * 10f else 0f)
        
        if (isActive) {
            // 1. TIA GIA TỐC (Warp Speed Lines bắn từ tâm ra ngoài)
            for (i in particles.indices) {
                val p = particles[i]
                p.radius += p.speed
                if (p.radius > innerRadius * 0.95f) {
                    particles[i] = createParticle(false)
                }
                
                val startX = cx + p.radius * cos(p.angle)
                val startY = cy + p.radius * sin(p.angle)
                val endX = cx + (p.radius + p.length) * cos(p.angle)
                val endY = cy + (p.radius + p.length) * sin(p.angle)
                
                paintSpeed.alpha = (p.alpha * (p.radius / innerRadius)).toInt().coerceIn(0, 255)
                paintSpeed.strokeWidth = 2f + (p.radius / innerRadius) * 5f
                paintSpeed.color = Color.parseColor("#00E5FF")
                canvas.drawLine(startX, startY, endX, endY, paintSpeed)
                
                // Lớp viền trắng chói lõi hạt
                paintSpeed.strokeWidth = paintSpeed.strokeWidth * 0.5f
                paintSpeed.color = Color.WHITE
                canvas.drawLine(startX, startY, endX, endY, paintSpeed)
            }
            
            // 2. VÒNG TURBINE XOAY (Spinning High-Tech Arcs)
            paintGlow.maskFilter = null
            paintGlow.strokeWidth = 4f
            paintGlow.alpha = 180
            
            val dash1 = DashPathEffect(floatArrayOf(50f, 30f), -time * 300f)
            paintGlow.pathEffect = dash1
            canvas.drawCircle(cx, cy, innerRadius * 0.7f, paintGlow)
            
            val dash2 = DashPathEffect(floatArrayOf(15f, 40f), time * 500f)
            paintGlow.pathEffect = dash2
            paintGlow.strokeWidth = 8f
            paintGlow.alpha = 255
            canvas.drawCircle(cx, cy, innerRadius * 0.85f, paintGlow)
            
            paintGlow.pathEffect = null
        } else {
            paintGlow.maskFilter = null
            paintGlow.strokeWidth = 4f
            paintGlow.alpha = 255
            canvas.drawCircle(cx, cy, innerRadius, paintGlow)
        }
        
        updatePaints() // Reset filter for next frame
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
