package com.networkoptimizer.vpn

object NativePacketEngine {
    init {
        System.loadLibrary("networkoptimizer")
    }

    @JvmStatic
    external fun stringFromJNI(): String
    
    @JvmStatic
    external fun startPacketProcessing(vpnFd: Int): Boolean
    
    @JvmStatic
    external fun stopPacketProcessing()
}
