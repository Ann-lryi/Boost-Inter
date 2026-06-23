package com.networkoptimizer.vpn

object NativePacketEngine {
    init {
        System.loadLibrary("networkoptimizer")
    }

    var onStatsUpdated: ((rx: Long, tx: Long, blocked: Long) -> Unit)? = null
    var onProtectSocket: ((Int) -> Boolean)? = null

    @JvmStatic
    external fun stringFromJNI(): String
    
    @JvmStatic
    external fun startPacketProcessing(vpnFd: Int): Boolean
    
    @JvmStatic
    external fun stopPacketProcessing()

    @JvmStatic
    fun onTrafficUpdated(packets: Long, bytes: Long, adsBlocked: Long) {
        onStatsUpdated?.invoke(packets, bytes, adsBlocked)
    }

    @JvmStatic
    fun protect(socketFd: Int): Boolean {
        return onProtectSocket?.invoke(socketFd) ?: false
    }
}