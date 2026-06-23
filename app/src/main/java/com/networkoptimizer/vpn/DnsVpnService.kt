package com.networkoptimizer.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*

class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }
        
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (vpnInterface != null) return

        try {
            val builder = Builder()
                .setSession("NetworkOptimizer DNS")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("1.1.1.1") // Cloudflare DNS
                .addDnsServer("1.0.0.1")
                .addRoute("0.0.0.0", 0) // Route all traffic for monitoring/processing
            
            vpnInterface = builder.establish()
            
            vpnInterface?.let { fd ->
                Log.i("DnsVpnService", "TUN Interface established with FD: ${fd.fd}")
                // Bắt đầu xử lý packets bằng C++ ở background
                serviceScope.launch {
                    NativePacketEngine.startPacketProcessing(fd.fd)
                }
            } ?: Log.e("DnsVpnService", "Failed to establish VPN interface")

        } catch (e: Exception) {
            Log.e("DnsVpnService", "Error starting VPN: ${e.message}")
            stopVpn()
        }
    }

    private fun stopVpn() {
        NativePacketEngine.stopPacketProcessing()
        serviceJob.cancel()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e("DnsVpnService", "Error closing VPN interface", e)
        }
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
