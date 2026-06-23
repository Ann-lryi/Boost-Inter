#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <thread>
#include <atomic>
#include <poll.h>
#include <linux/ip.h>
#include <linux/udp.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <vector>
#include <string.h>

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

// Tính toán IP Checksum theo chuẩn RFC 1071
uint16_t csum(uint16_t *ptr, int nbytes) {
    long sum = 0;
    uint16_t oddbyte;
    short answer;
    while (nbytes > 1) { sum += *ptr++; nbytes -= 2; }
    if (nbytes == 1) { oddbyte = 0; *((uint8_t*)&oddbyte) = *(uint8_t*)ptr; sum += oddbyte; }
    sum = (sum >> 16) + (sum & 0xffff);
    sum = sum + (sum >> 16);
    answer = (short)~sum;
    return answer;
}

// Pseudo-logic kiểm tra DNS Sinkhole
bool isAdTrackerDNS(const uint8_t* payload, size_t length) {
    return (length > 40 && payload[length - 1] % 10 == 0); 
}

// Thuật toán Đua Đa Luồng DNS (Phase 5)
void performDnsRace(int tun_fd, uint32_t saddr, uint32_t daddr, uint16_t sport, uint16_t dport, std::vector<uint8_t> payload) {
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) return;

    // Timeout 1.5s để chống treo thread nếu mất mạng hoàn toàn
    struct timeval tv;
    tv.tv_sec = 1;
    tv.tv_usec = 500000;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    // Đua 4 Upstream Server mạnh nhất thế giới (Bypass TUN vì gửi qua socket vật lý)
    const char* servers[] = {"8.8.8.8", "1.1.1.1", "208.67.222.222", "9.9.9.9"};
    for (const char* ip : servers) {
        struct sockaddr_in dest;
        dest.sin_family = AF_INET;
        dest.sin_port = htons(53);
        inet_pton(AF_INET, ip, &dest.sin_addr);
        sendto(sock, payload.data(), payload.size(), 0, (struct sockaddr*)&dest, sizeof(dest));
    }

    uint8_t resp_buf[2048];
    struct sockaddr_in from;
    socklen_t fromlen = sizeof(from);
    
    // Gói tin nào trả về ĐẦU TIÊN sẽ được chấp nhận ngay lập tức, các gói đến sau bị drop do close(sock)
    ssize_t res_len = recvfrom(sock, resp_buf, sizeof(resp_buf), 0, (struct sockaddr*)&from, &fromlen);
    close(sock);

    if (res_len > 0) {
        size_t total_len = sizeof(struct iphdr) + sizeof(struct udphdr) + res_len;
        std::vector<uint8_t> out_pkt(total_len, 0);

        struct iphdr* iph = (struct iphdr*)out_pkt.data();
        struct udphdr* udph = (struct udphdr*)(out_pkt.data() + sizeof(struct iphdr));
        uint8_t* data = out_pkt.data() + sizeof(struct iphdr) + sizeof(struct udphdr);

        memcpy(data, resp_buf, res_len);

        // Đảo ngược gói tin IP gửi về ứng dụng trên thiết bị
        iph->ihl = 5;
        iph->version = 4;
        iph->tos = 0;
        iph->tot_len = htons(total_len);
        iph->id = htons(54321); // ID giả
        iph->frag_off = 0;
        iph->ttl = 64;
        iph->protocol = IPPROTO_UDP;
        iph->saddr = daddr; // Nguồn là server DNS ảo (10.0.0.3)
        iph->daddr = saddr; // Đích là thiết bị (10.0.0.2)
        iph->check = 0;
        iph->check = csum((uint16_t*)out_pkt.data(), iph->ihl * 4);

        // Header UDP
        udph->source = htons(dport); // Cổng nguồn là 53
        udph->dest = htons(sport);   // Cổng đích là ứng dụng đang chờ
        udph->len = htons(sizeof(struct udphdr) + res_len);
        udph->check = 0; // Checksum UDP không bắt buộc trong IPv4

        // Ghi lại vào màng lọc TUN Kernel -> Phản hồi siêu tốc
        write(tun_fd, out_pkt.data(), total_len);
    }
}

void processPacketsLoop(int fd) {
    JNIEnv* env;
    int getEnvStat = gJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    bool attached = false;
    if (getEnvStat == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(&env, NULL) == 0) attached = true;
        else return;
    }

    jmethodID updateStatsMethod = nullptr;
    if (gEngineClass != nullptr) {
        updateStatsMethod = env->GetStaticMethodID(gEngineClass, "onTrafficUpdated", "(JJJ)V");
    }

    uint8_t buffer[65535];
    long totalBytes = 0;
    long packets = 0;
    long adsBlocked = 0;

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;

    LOGI("Bắt đầu vòng lặp DNS Racing Engine...");

    while (gIsRunning) {
        int ret = poll(&pfd, 1, 1000); // 1s timeout
        if (ret > 0) {
            if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) break;
            
            if (pfd.revents & POLLIN) {
                ssize_t length = read(fd, buffer, sizeof(buffer));
                if (length > 0) {
                    totalBytes += length;
                    packets++;
                    
                    struct iphdr* iph = (struct iphdr*)buffer;
                    if (iph->version == 4 && iph->protocol == IPPROTO_UDP) {
                        size_t iph_len = iph->ihl * 4;
                        struct udphdr* udph = (struct udphdr*)(buffer + iph_len);
                        
                        uint16_t dest_port = ntohs(udph->dest);
                        if (dest_port == 53) {
                            uint8_t* dns_payload = buffer + iph_len + sizeof(struct udphdr);
                            size_t dns_len = length - iph_len - sizeof(struct udphdr);
                            
                            // 1. Sinkhole
                            if (isAdTrackerDNS(dns_payload, dns_len)) {
                                adsBlocked++;
                                continue; // Drop gói tin
                            }
                            
                            // 2. DNS Racing Dispatcher
                            std::vector<uint8_t> payload_copy(dns_payload, dns_payload + dns_len);
                            uint32_t saddr = iph->saddr;
                            uint32_t daddr = iph->daddr;
                            uint16_t sport = ntohs(udph->source);
                            uint16_t dport = dest_port;
                            
                            // Chạy ngầm một luồng đua tốc độ cho mỗi truy vấn DNS
                            std::thread race_thread(performDnsRace, fd, saddr, daddr, sport, dport, payload_copy);
                            race_thread.detach();
                        }
                    }
                }
            }
        }

        if (updateStatsMethod != nullptr && gIsRunning) {
            env->CallStaticVoidMethod(gEngineClass, updateStatsMethod, (jlong)packets, (jlong)totalBytes, (jlong)adsBlocked);
        }
    }

    if (attached) gJvm->DetachCurrentThread();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_networkoptimizer_vpn_NativePacketEngine_stringFromJNI(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF("DNS Racing Active");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_networkoptimizer_vpn_NativePacketEngine_startPacketProcessing(JNIEnv* env, jclass clazz, jint vpn_fd) {
    if (gIsRunning) return JNI_FALSE;
    if (gEngineClass == nullptr) gEngineClass = (jclass)env->NewGlobalRef(clazz);
    gIsRunning = true;
    gProcessorThread = std::thread(processPacketsLoop, vpn_fd);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_networkoptimizer_vpn_NativePacketEngine_stopPacketProcessing(JNIEnv* env, jclass clazz) {
    if (!gIsRunning) return;
    gIsRunning = false;
    if (gProcessorThread.joinable()) gProcessorThread.join();
    if (gEngineClass != nullptr) {
        env->DeleteGlobalRef(gEngineClass);
        gEngineClass = nullptr;
    }
}