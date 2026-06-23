package com.networkoptimizer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.networkoptimizer.databinding.ActivityMainBinding
import com.networkoptimizer.vpn.DnsVpnService
import com.networkoptimizer.vpn.NativePacketEngine

class MainActivity : AppCompatActivity() {

    private val VPN_REQUEST_CODE = 0x0F
    private lateinit var binding: ActivityMainBinding
    private var isVpnActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndroid16Permissions()

        // Setup callback từ C++
        NativePacketEngine.onStatsUpdated = { packets, bytes, blocked ->
            runOnUiThread {
                if (isVpnActive) {
                    binding.tvPackets.text = "$packets"
                    binding.tvBytes.text = formatBytes(bytes)
                    binding.tvBlocked.text = "$blocked"
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

    private fun checkAndroid16Permissions() {
        // 1. Xin quyền hiển thị Thông báo (bắt buộc trên Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        // 2. Chống lại Phantom Process Killer của Android 14/15/16
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                Toast.makeText(this, "Vui lòng cho phép bỏ qua tối ưu pin để C++ Engine chạy tối đa công suất", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
            binding.tvStatus.text = "LEVIATHAN ENGINE"
            binding.tvDnsInfo.text = "Gatling 6x | Priority -20 | 2MB Buf"
            binding.tvStatus.setTextColor(Color.parseColor("#00E5FF")) // Cyber neon cyan
            binding.imgStatus.setColorFilter(Color.parseColor("#00E5FF"))
        } else {
            binding.tvStatus.text = "SYSTEM STANDBY"
            binding.tvDnsInfo.text = "Route: None"
            binding.tvStatus.setTextColor(Color.parseColor("#4B5563")) // status_off
            binding.imgStatus.setColorFilter(Color.parseColor("#4B5563"))
            binding.tvPackets.text = "0"
            binding.tvBytes.text = "0.00 B"
            binding.tvBlocked.text = "0"
        }
    }
}