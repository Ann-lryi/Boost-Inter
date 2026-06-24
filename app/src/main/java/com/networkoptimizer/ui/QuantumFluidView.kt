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
    
    // Paint cho hiệu ứng Hạt ánh sáng (Light Particles)
    private val paintParticle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }

    private var animator: ValueAnimator? = null

    // Cấu trúc Hạt ánh sáng trôi nổi chậm rãi (Brownian Motion)
    private class Particle(
        var x: Float, 
        var y: Float, 
        var vx: Float, 
        var vy: Float, 
        var size: Float,
        var life: Float,
        var maxLife: Float
    )
    
    private val particles = ArrayList<Particle>()
    private val MAX_PARTICLES = 40

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) 
        updatePaints()
    }

    private fun spawnParticle(cx: Float, cy: Float, radius: Float): Particle {
        // Sinh ra hạt ngẫu nhiên bên trong lõi lượng tử
        val angle = Random.nextFloat() * Math.PI * 2
        val r = Random.nextFloat() * (radius * 0.8f) // Nằm trong 80% lõi
        
        return Particle(
            x = cx + r.toFloat() * cos(angle).toFloat(),
            y = cy + r.toFloat() * sin(angle).toFloat(),
            // Vận tốc cực chậm, trôi nổi (từ -1.5 đến 1.5)
            vx = (Random.nextFloat() - 0.5f) * 3f,
            vy = (Random.nextFloat() - 0.5f) * 3f,
            size = Random.nextFloat() * 4f + 2f, // Kích thước hạt từ 2px đến 6px
            life = 0f,
            maxLife = Random.nextFloat() * 100f + 50f // Tuổi thọ hạt (vòng đời fade in/out)
        )
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
            particles.clear()
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
            duration = 4000 // Fluid cycle chậm 4 giây (originOS style)
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
            // DUY TRÌ SỐ LƯỢNG HẠT ÁNH SÁNG
            if (particles.size < MAX_PARTICLES && Random.nextFloat() < 0.2f) {
                particles.add(spawnParticle(cx, cy, innerRadius))
            }
            
            // XỬ LÝ VÀ VẼ TỪNG HẠT
            val iterator = particles.iterator()
            while (iterator.hasNext()) {
                val p = iterator.next()
                
                // Cập nhật vị trí chậm rãi
                p.x += p.vx
                p.y += p.vy
                p.life += 1f
                
                // Toán học Fade In / Fade Out (Alpha curve: Parabola)
                val lifeRatio = p.life / p.maxLife
                val alpha = (sin(lifeRatio * Math.PI) * 255).toInt().coerceIn(0, 255)
                
                if (p.life >= p.maxLife) {
                    iterator.remove() // Hạt chết, xóa khỏi danh sách
                    continue
                }
                
                // Vẽ hạt ánh sáng với lõi trắng, viền Glow xanh
                paintParticle.alpha = alpha
                paintParticle.maskFilter = BlurMaskFilter(p.size * 1.5f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawCircle(p.x, p.y, p.size, paintParticle)
                
                // Lõi sáng trắng
                paintParticle.color = Color.WHITE
                paintParticle.maskFilter = null
                canvas.drawCircle(p.x, p.y, p.size * 0.5f, paintParticle)
                paintParticle.color = Color.parseColor("#00E5FF") // reset màu
            }
        } else {
            // Chế độ Standby
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
