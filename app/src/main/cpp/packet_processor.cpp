#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <thread>
#include <atomic>
#include <poll.h>

#define LOG_TAG "NativePacketProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JavaVM* gJvm = nullptr;
std::atomic<bool> gIsRunning(false);
std::thread gProcessorThread;
jclass gEngineClass = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

void processPacketsLoop(int fd) {
    JNIEnv* env;
    int getEnvStat = gJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    bool attached = false;
    if (getEnvStat == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(&env, NULL) == 0) {
            attached = true;
        } else {
            LOGE("Failed to attach thread");
            return;
        }
    }

    jmethodID updateStatsMethod = nullptr;
    if (gEngineClass != nullptr) {
        updateStatsMethod = env->GetStaticMethodID(gEngineClass, "onTrafficUpdated", "(JJ)V");
    }

    uint8_t buffer[65535];
    long totalBytes = 0;
    long packets = 0;

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;

    LOGI("Bắt đầu vòng lặp đọc packets trên FD: %d", fd);

    while (gIsRunning) {
        int ret = poll(&pfd, 1, 1000); // 1s timeout
        if (ret > 0) {
            if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
                LOGI("FD closed or error, stopping loop.");
                break;
            }
            if (pfd.revents & POLLIN) {
                ssize_t length = read(fd, buffer, sizeof(buffer));
                if (length < 0) {
                    LOGE("Error reading from TUN interface.");
                    break;
                }
                if (length > 0) {
                    totalBytes += length;
                    packets++;
                    
                    // PHASE 3: Tại đây chỉ thống kê packets. 
                    // Tương lai sẽ thêm C++ logic (Proxy/NAT) để forward UDP port 53.
                }
            }
        }

        // Gửi callback về Kotlin mỗi chu kỳ
        if (updateStatsMethod != nullptr && gIsRunning) {
            env->CallStaticVoidMethod(gEngineClass, updateStatsMethod, (jlong)packets, (jlong)totalBytes);
        }
    }

    LOGI("Kết thúc vòng lặp đọc packets.");

    if (attached) {
        gJvm->DetachCurrentThread();
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_networkoptimizer_vpn_NativePacketEngine_stringFromJNI(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF("C++ Packet Engine Active");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_networkoptimizer_vpn_NativePacketEngine_startPacketProcessing(JNIEnv* env, jclass clazz, jint vpn_fd) {
    if (gIsRunning) return JNI_FALSE;
    
    if (gEngineClass == nullptr) {
        gEngineClass = (jclass)env->NewGlobalRef(clazz);
    }

    gIsRunning = true;
    gProcessorThread = std::thread(processPacketsLoop, vpn_fd);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_networkoptimizer_vpn_NativePacketEngine_stopPacketProcessing(JNIEnv* env, jclass clazz) {
    if (!gIsRunning) return;
    gIsRunning = false;
    if (gProcessorThread.joinable()) {
        gProcessorThread.join();
    }
    if (gEngineClass != nullptr) {
        env->DeleteGlobalRef(gEngineClass);
        gEngineClass = nullptr;
    }
}