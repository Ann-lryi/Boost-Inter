package com.networkoptimizer

import android.animation.ArgbEvaluator
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
import com.networkoptimizer.ui.SpringInterpolator
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

    private fun popView(view: View) {
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(600)
            .setInterpolator(SpringInterpolator(0.3f))
            .start()
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
        
        // OriginOS Spring Animations for Widgets
        popView(binding.widgetData)
        popView(binding.widgetThreats)
        popView(binding.widgetPackets)
        popView(binding.cardAction)
        
        if (active) {
            binding.fluidCore.setActive(true)
            
            // Ambient Lighting Fade In
            binding.ambientGlow.animate().alpha(1f).setDuration(1000).start()

            binding.tvAppTitle.setTextColor(Color.parseColor("#00E5FF"))
            binding.tvActionTitle.setTextColor(Color.parseColor("#00E5FF"))
            binding.tvDnsInfo.text = "Priority -20 | 6x Gatling Active"
            binding.tvDnsInfo.setTextColor(Color.parseColor("#8000E5FF"))

        } else {
            binding.fluidCore.setActive(false)
            
            // Ambient Lighting Fade Out
            binding.ambientGlow.animate().alpha(0f).setDuration(800).start()

            binding.tvAppTitle.setTextColor(Color.parseColor("#FFFFFF"))
            binding.tvActionTitle.setTextColor(Color.parseColor("#FFFFFF"))
            binding.tvDnsInfo.text = "System Standby"
            binding.tvDnsInfo.setTextColor(Color.parseColor("#99A2B3"))
            
            currentPackets = 0
            currentBlocked = 0
            binding.tvPackets.text = "0"
            binding.tvBytes.text = "0 B"
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}