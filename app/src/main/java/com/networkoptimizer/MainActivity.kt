package com.networkoptimizer

import android.animation.ObjectAnimator
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
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.networkoptimizer.databinding.ActivityMainBinding
import com.networkoptimizer.vpn.DnsVpnService
import com.networkoptimizer.vpn.NativePacketEngine

class MainActivity : AppCompatActivity() {

    private val VPN_REQUEST_CODE = 0x0F
    private lateinit var binding: ActivityMainBinding
    private var isVpnActive = false
    
    // Cached Stats for smooth counting
    private var currentPackets: Long = 0
    private var currentBlocked: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndroid16Permissions()

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

    private fun animateNumber(textView: android.widget.TextView, start: Long, end: Long) {
        if (start == end) return
        val animator = ValueAnimator.ofFloat(start.toFloat(), end.toFloat())
        animator.duration = 1000 // Very smooth 1s catch-up (OriginOS style)
        animator.interpolator = DecelerateInterpolator(1.5f)
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
        
        // Cải tiến UX 1: Giảm độ sáng viền nền thay vì làm giật khung hình (No Pop Lag)
        val bgTargetAlpha = if (active) 40 else 255
        val bgTargetElevation = if (active) 2f else 10f // Đẩy sâu Widget xuống lớp nền
        
        animateBackground(binding.widgetData, bgTargetAlpha, bgTargetElevation)
        animateBackground(binding.widgetThreats, bgTargetAlpha, bgTargetElevation)
        animateBackground(binding.widgetPackets, bgTargetAlpha, bgTargetElevation)
        animateBackground(binding.cardAction, bgTargetAlpha, bgTargetElevation)
        
        if (active) {
            binding.fluidCore.setActive(true)
            binding.ambientGlow.animate().alpha(1f).setDuration(1000).start()

            // Cải tiến UX 2: Chữ phát sáng Neon khi viền mờ đi (Tạo tương phản cực độ)
            applyNeonText(binding.tvAppTitle, true)
            applyNeonText(binding.tvActionTitle, true)
            applyNeonText(binding.tvPackets, true)
            applyNeonText(binding.tvBytes, true)
            applyNeonText(binding.tvBlocked, true)

            binding.tvDnsInfo.text = "Priority -20 | 6x Gatling Active"
            binding.tvDnsInfo.setTextColor(Color.parseColor("#00E5FF"))

        } else {
            binding.fluidCore.setActive(false)
            binding.ambientGlow.animate().alpha(0f).setDuration(800).start()

            applyNeonText(binding.tvAppTitle, false)
            applyNeonText(binding.tvActionTitle, false)
            applyNeonText(binding.tvPackets, false)
            applyNeonText(binding.tvBytes, false)
            applyNeonText(binding.tvBlocked, false)

            binding.tvDnsInfo.text = "System Standby"
            binding.tvDnsInfo.setTextColor(Color.parseColor("#99A2B3"))
            
            currentPackets = 0
            currentBlocked = 0
            binding.tvPackets.text = "0"
            binding.tvBytes.text = "0 B"
            binding.tvBlocked.text = "0"
        }
    }

    private fun animateBackground(view: View, targetAlpha: Int, targetElevation: Float) {
        view.background?.mutate()?.let { bg ->
            ObjectAnimator.ofInt(bg, "alpha", targetAlpha).apply {
                duration = 500
                start()
            }
        }
        view.animate().translationZ(targetElevation - view.elevation).setDuration(500).start()
    }

    private fun applyNeonText(tv: android.widget.TextView, isOn: Boolean) {
        if (isOn) {
            tv.setTextColor(Color.parseColor("#FFFFFF"))
            tv.setShadowLayer(15f, 0f, 0f, Color.parseColor("#00E5FF"))
        } else {
            tv.setTextColor(Color.parseColor("#FFFFFF"))
            tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}