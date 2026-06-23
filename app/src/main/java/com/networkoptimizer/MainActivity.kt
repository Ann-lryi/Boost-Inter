package com.networkoptimizer

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.networkoptimizer.vpn.DnsVpnService
import com.networkoptimizer.vpn.NativePacketEngine

class MainActivity : AppCompatActivity() {

    private val VPN_REQUEST_CODE = 0x0F

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple layout building in code to avoid XML layout for now, to keep it simple and bug-free
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
        }

        val statusText = TextView(this).apply {
            text = "Trạng thái: Chưa kết nối\n" + NativePacketEngine.stringFromJNI()
            textSize = 18f
        }
        
        val btnStart = Button(this).apply {
            text = "Bật Tối Ưu (Đổi DNS 1.1.1.1)"
            setOnClickListener {
                startVpnFlow()
            }
        }

        val btnStop = Button(this).apply {
            text = "Tắt"
            setOnClickListener {
                stopVpnService()
                statusText.text = "Trạng thái: Đã tắt"
            }
        }

        layout.addView(statusText)
        layout.addView(btnStart)
        layout.addView(btnStop)

        setContentView(layout)
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
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val serviceIntent = Intent(this, DnsVpnService::class.java)
            startService(serviceIntent)
            Toast.makeText(this, "Đã bật DNS Changer", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Cần cấp quyền VPN để hoạt động", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVpnService() {
        val serviceIntent = Intent(this, DnsVpnService::class.java).apply {
            action = "STOP"
        }
        startService(serviceIntent)
    }
}
