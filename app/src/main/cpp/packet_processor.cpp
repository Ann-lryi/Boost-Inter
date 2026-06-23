#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>

#define LOG_TAG "NativePacketProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_networkoptimizer_vpn_NativePacketEngine_stringFromJNI(
        JNIEnv* env,
        jclass /* clazz */) {
    std::string hello = "Native Module (C++) Ready for Packet Processing";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_networkoptimizer_vpn_NativePacketEngine_startPacketProcessing(
        JNIEnv* env,
        jclass /* clazz */,
        jint vpn_fd) {
    
    LOGI("Bắt đầu xử lý packets trên File Descriptor: %d", vpn_fd);
    
    // TODO: Implement epoll loop reading IP packets from vpn_fd
    // Parse IPv4/IPv6, UDP headers, and manipulate DNS queries (Port 53).
    
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_networkoptimizer_vpn_NativePacketEngine_stopPacketProcessing(
        JNIEnv* env,
        jclass /* clazz */) {
    LOGI("Dừng xử lý packets.");
    // TODO: Signal the epoll loop to stop
}
