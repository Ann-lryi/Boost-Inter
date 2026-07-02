package com.networkoptimizer.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class DnsVpnService : VpnService() {

    companion object {
        // FIX: kênh callback trạng thái thật, để UI không còn "giả vờ hoạt động"
        var onStateChanged: ((active: Boolean, error: String?) -> Unit)? = null
        private const val MAX_ESTABLISH_RETRIES = 2
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
        val channelId = "network_optimizer_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Network Optimizer Status",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Network Optimizer")
            .setContentText("Đang tối ưu DNS...")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startVpn(retryCount: Int = 0) {
        if (vpnInterface != null) return

        try {
            NativePacketEngine.onProtectSocket = { fd ->
                protect(fd)
            }

            val builder = Builder()
                .setSession("NETWORK OPTIMIZER")
                .setMtu(1280)
                .setBlocking(false)
                .addAddress("10.0.0.2", 32)
                .addDnsServer("10.0.0.3")
                .addRoute("10.0.0.3", 32)

            vpnInterface = builder.establish()

            vpnInterface?.let { fd ->
                Log.i("DnsVpnService", "TUN Interface established with FD: ${fd.fd}")
                serviceScope.launch {
                    val started = NativePacketEngine.startPacketProcessing(fd.fd)
                    withContext(Dispatchers.Main) {
                        if (started) {
                            registerNetworkWatcher()
                            onStateChanged?.invoke(true, null)
                        } else {
                            onStateChanged?.invoke(false, "Không thể khởi động engine xử lý gói tin")
                            stopVpn()
                        }
                    }
                }
            } ?: run {
                Log.e("DnsVpnService", "Failed to establish VPN interface")
                // FIX: thử lại tối đa MAX_ESTABLISH_RETRIES lần trước khi báo lỗi hẳn
                if (retryCount < MAX_ESTABLISH_RETRIES) {
                    serviceScope.launch {
                        delay(500)
                        withContext(Dispatchers.Main) { startVpn(retryCount + 1) }
                    }
                } else {
                    onStateChanged?.invoke(false, "Không thể tạo VPN interface. Có thể có VPN khác đang chạy.")
                    stopSelf()
                }
            }

        } catch (e: Exception) {
            Log.e("DnsVpnService", "Error starting VPN: ${e.message}")
            onStateChanged?.invoke(false, e.message)
            stopVpn()
        }
    }

    // FIX: theo dõi đổi mạng Wi-Fi <-> Cellular để tunnel không bị "chết" âm thầm
    private fun registerNetworkWatcher() {
        if (connectivityManager == null) {
            connectivityManager = getSystemService(ConnectivityManager::class.java)
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Gắn lại tunnel vào mạng đang hoạt động để tránh gói tin bị "kẹt" trên mạng cũ
                vpnInterface?.let {
                    try {
                        setUnderlyingNetworks(arrayOf(network))
                        Log.i("DnsVpnService", "Đã chuyển engine sang mạng mới")
                    } catch (e: Exception) {
                        Log.e("DnsVpnService", "Lỗi khi chuyển mạng", e)
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.i("DnsVpnService", "Mất mạng hiện tại, chờ mạng thay thế...")
            }
        }
        connectivityManager?.registerNetworkCallback(request, networkCallback as ConnectivityManager.NetworkCallback)
    }

    private fun unregisterNetworkWatcher() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.e("DnsVpnService", "Lỗi khi huỷ đăng ký network watcher", e)
        }
        networkCallback = null
    }

    private fun stopVpn() {
        serviceScope.launch(Dispatchers.IO) {
            NativePacketEngine.stopPacketProcessing()
            withContext(Dispatchers.Main) {
                unregisterNetworkWatcher()
                try {
                    vpnInterface?.close()
                } catch (e: Exception) {
                    Log.e("DnsVpnService", "Error closing VPN interface", e)
                }
                vpnInterface = null
                onStateChanged?.invoke(false, null)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
