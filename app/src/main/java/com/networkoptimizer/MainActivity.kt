package com.networkoptimizer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
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

        // Setup callback từ C++
        NativePacketEngine.onStatsUpdated = { packets, bytes ->
            runOnUiThread {
                if (isVpnActive) {
                    binding.tvPackets.text = "$packets"
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
            binding.tvStatus.text = "ENGINE ACTIVE"
            binding.tvDnsInfo.text = "Route: 1.1.1.1 (Cloudflare)"
            binding.tvStatus.setTextColor(Color.parseColor("#00FFA3")) // accent green
            binding.imgStatus.setColorFilter(Color.parseColor("#00FFA3"))
        } else {
            binding.tvStatus.text = "SYSTEM STANDBY"
            binding.tvDnsInfo.text = "Route: None"
            binding.tvStatus.setTextColor(Color.parseColor("#4B5563")) // status_off
            binding.imgStatus.setColorFilter(Color.parseColor("#4B5563"))
            binding.tvPackets.text = "0"
            binding.tvBytes.text = "0.00 B"
        }
    }
}