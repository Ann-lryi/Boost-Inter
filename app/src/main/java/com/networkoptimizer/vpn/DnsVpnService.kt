package com.networkoptimizer.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
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
        
        createForegroundNotification()
        startVpn()
        return START_STICKY
    }

    private fun createForegroundNotification() {
        val channelId = "leviathan_engine_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Leviathan Engine Status",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DNS Optimizer Active")
            .setContentText("Caching and racing DNS lookups against 4 public resolvers.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Android 14+ FGS requirements
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startVpn() {
        if (vpnInterface != null) return

        try {
            // Liên kết socket C++ với Kernel Android để tránh bị chặn bởi Firewall của Android 15/16
            NativePacketEngine.onProtectSocket = { fd -> 
                protect(fd) 
            }

            val builder = Builder()
                .setSession("LEVIATHAN ENGINE")
                .setMtu(1280) // Cực hạn chống phân mảnh
                .setBlocking(false) // Đột phá: Đặt TUN vào trạng thái Non-blocking để xử lý luồng cực nhanh
                .addAddress("10.0.0.2", 32)
                .addDnsServer("10.0.0.3") 
                .addRoute("10.0.0.3", 32) 
            
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
        // ĐỘT PHÁ UX: Đưa quá trình tắt xuống Background Thread để không làm đơ Main UI chờ C++ Thread Join
        serviceScope.launch(Dispatchers.IO) {
            NativePacketEngine.stopPacketProcessing()
            withContext(Dispatchers.Main) {
                try {
                    vpnInterface?.close()
                } catch (e: Exception) {
                    Log.e("DnsVpnService", "Error closing VPN interface", e)
                }
                vpnInterface = null
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
