package com.networkoptimizer

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.networkoptimizer.databinding.ActivityMainBinding
import com.networkoptimizer.vpn.DnsVpnService
import com.networkoptimizer.vpn.NativePacketEngine

class MainActivity : AppCompatActivity() {

    private val VPN_REQUEST_CODE = 0x0F
    private lateinit var binding: ActivityMainBinding
    private var isVpnActive = false

    // Animations
    private var coreRotationAnimator: ObjectAnimator? = null
    private var coreGlowAnimator: ObjectAnimator? = null
    
    // Cached Stats for smooth counting
    private var currentPackets: Long = 0
    private var currentBlocked: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndroid16Permissions()
        setupAnimations()

        NativePacketEngine.onStatsUpdated = { packets, bytes, blocked ->
            runOnUiThread {
                if (isVpnActive) {
                    animateNumber(binding.tvPackets, currentPackets, packets)
                    animateNumber(binding.tvBlocked, currentBlocked, blocked)
                    currentPackets = packets
                    currentBlocked = blocked
                    binding.tvBytes.text = formatBytes(bytes)
                }
            }
        }

        binding.switchVpn.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isVpnActive) {
                startVpnFlow()
            } else if (!isChecked && isVpnActive) {
                stopVpnService()
            }
        }
    }

    private fun setupAnimations() {
        // Infinite rotation for the Core
        coreRotationAnimator = ObjectAnimator.ofFloat(binding.imgCore, "rotation", 0f, 360f).apply {
            duration = 20000 // 20s full rotation
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }

        // Breathing glow effect
        val scaleX = PropertyValuesHolder.ofFloat("scaleX", 1f, 1.3f)
        val scaleY = PropertyValuesHolder.ofFloat("scaleY", 1f, 1.3f)
        val alpha = PropertyValuesHolder.ofFloat("alpha", 0.3f, 0.8f)
        coreGlowAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.coreGlow, scaleX, scaleY, alpha).apply {
            duration = 1500
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
    }

    private fun animateNumber(textView: android.widget.TextView, start: Long, end: Long) {
        if (start == end) return
        val animator = ValueAnimator.ofFloat(start.toFloat(), end.toFloat())
        animator.duration = 800 // Smooth 0.8s catch-up
        animator.addUpdateListener { animation ->
            textView.text = (animation.animatedValue as Float).toLong().toString()
        }
        animator.start()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.2f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.2f MB", mb)
    }

    private fun startVpnFlow() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            onActivityResult(VPN_REQUEST_CODE, Activity.RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                val serviceIntent = Intent(this, DnsVpnService::class.java)
                startService(serviceIntent)
                updateUiState(true)
            } else {
                binding.switchVpn.isChecked = false
                Toast.makeText(this, "Cần cấp quyền VPN để hoạt động", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopVpnService() {
        val serviceIntent = Intent(this, DnsVpnService::class.java).apply {
            action = "STOP"
        }
        startService(serviceIntent)
        updateUiState(false)
    }

    private fun updateUiState(active: Boolean) {
        isVpnActive = active
        if (active) {
            binding.tvStatus.text = "HYPER-SPEED ACTIVE"
            binding.tvStatus.setTextColor(Color.parseColor("#00E5FF")) // Neon Cyan
            binding.tvDnsInfo.text = "Priority: -20 (Max) | Mutex Cache"
            
            // Transform Widgets to "ON" state
            binding.cardAction.setBackgroundResource(R.drawable.bg_widget_on)
            binding.widgetData.setBackgroundResource(R.drawable.bg_widget_on)
            binding.widgetThreats.setBackgroundResource(R.drawable.bg_widget_on)
            binding.widgetPackets.setBackgroundResource(R.drawable.bg_widget_on)
            
            coreRotationAnimator?.start()
            coreGlowAnimator?.start()
            binding.coreGlow.animate().alpha(1f).setDuration(500).start()
        } else {
            binding.tvStatus.text = "STANDBY"
            binding.tvStatus.setTextColor(Color.parseColor("#8B9BB4"))
            binding.tvDnsInfo.text = "Priority: Idle"
            
            // Revert Widgets
            binding.cardAction.setBackgroundResource(R.drawable.bg_widget_off)
            binding.widgetData.setBackgroundResource(R.drawable.bg_widget_off)
            binding.widgetThreats.setBackgroundResource(R.drawable.bg_widget_off)
            binding.widgetPackets.setBackgroundResource(R.drawable.bg_widget_off)
            
            coreRotationAnimator?.cancel()
            coreGlowAnimator?.cancel()
            binding.coreGlow.animate().alpha(0f).setDuration(500).start()
            
            currentPackets = 0
            currentBlocked = 0
            binding.tvPackets.text = "0"
            binding.tvBytes.text = "0.00 B"
            binding.tvBlocked.text = "0"
        }
    }

    private fun checkAndroid16Permissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                Toast.makeText(this, "Vui lòng cho phép bỏ qua tối ưu pin để Leviathan chạy mượt mà nhất", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}