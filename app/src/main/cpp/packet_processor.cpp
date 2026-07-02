#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <thread>
#include <atomic>
#include <poll.h>
#include <linux/ip.h>
#include <netinet/udp.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <vector>
#include <string.h>
#include <unordered_map>
#include <unordered_set>
#include <shared_mutex>
#include <sys/resource.h>
#include <chrono>

#define LOG_TAG "NativePacketProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JavaVM* gJvm = nullptr;
std::atomic<bool> gIsRunning(false);
std::thread gProcessorThread;
jclass gEngineClass = nullptr;

// FIX #3: giới hạn số luồng "đua DNS" chạy song song để tránh bão thread làm máy ì/nóng
static constexpr int kMaxConcurrentRaces = 24;
std::atomic<int> gActiveRaces(0);

// FIX #4: cache DNS có TTL thật + không cache phản hồi lỗi
struct CacheEntry {
    std::vector<uint8_t> data;
    int64_t expiresAtMs;
};
std::unordered_map<std::string, CacheEntry> gDnsCache;
std::shared_mutex gCacheMutex;

// FIX #1: danh sách hậu tố domain quảng cáo/tracker thật (rút gọn, có thể mở rộng)
static const std::unordered_set<std::string> kAdTrackerSuffixes = {
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "adservice.google.com", "graph.facebook.com/marketing", "ads.facebook.com",
    "adnxs.com", "adsrvr.org", "criteo.com", "taboola.com", "outbrain.com",
    "scorecardresearch.com", "moatads.com", "mopub.com", "unityads.unity3d.com",
    "app-measurement.com", "adjust.com", "vungle.com", "chartboost.com",
    "applovin.com", "startappexchange.com", "flurry.com", "amplitude.com",
};

static int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}

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

// FIX #1: đọc đúng QNAME theo format DNS (chuỗi độ dài + nhãn) thay vì đoán ngẫu nhiên
static std::string parseQName(const uint8_t* payload, size_t length) {
    std::string name;
    if (length <= 12) return name;
    size_t pos = 12; // bỏ qua header 12 byte
    while (pos < length) {
        uint8_t labelLen = payload[pos];
        if (labelLen == 0) break;
        if (labelLen & 0xC0) break; // nén DNS, bỏ qua để đơn giản/an toàn
        pos++;
        if (pos + labelLen > length) break;
        if (!name.empty()) name += '.';
        name.append((const char*)payload + pos, labelLen);
        pos += labelLen;
    }
    // hạ chữ thường để so khớp ổn định
    for (auto& c : name) c = (char)tolower(c);
    return name;
}

static bool isAdTrackerDomain(const std::string& domain) {
    if (domain.empty()) return false;
    for (const auto& suffix : kAdTrackerSuffixes) {
        if (domain == suffix) return true;
        if (domain.size() > suffix.size() &&
            domain.compare(domain.size() - suffix.size(), suffix.size(), suffix) == 0 &&
            domain[domain.size() - suffix.size() - 1] == '.') {
            return true;
        }
    }
    return false;
}

// FIX #2: gọi ngược lên Kotlin để protect() socket, tránh vòng lặp định tuyến khi mở rộng route
static bool protectNativeSocket(int fd) {
    if (gJvm == nullptr || gEngineClass == nullptr) return true; // fail-open, không chặn hoạt động
    JNIEnv* env = nullptr;
    bool attached = false;
    int stat = gJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (stat == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(&env, NULL) != 0) return true;
        attached = true;
    }
    bool result = true;
    jmethodID protectMethod = env->GetStaticMethodID(gEngineClass, "protect", "(I)Z");
    if (protectMethod != nullptr) {
        result = env->CallStaticBooleanMethod(gEngineClass, protectMethod, fd);
    }
    if (attached) gJvm->DetachCurrentThread();
    return result;
}

// FIX #4: kiểm tra RCODE của phản hồi DNS - chỉ cache khi NOERROR (0)
static bool isSuccessResponse(const std::vector<uint8_t>& resp) {
    if (resp.size() < 4) return false;
    uint8_t rcode = resp[3] & 0x0F;
    return rcode == 0;
}

// FIX #4: đọc TTL (giây) của bản ghi Answer đầu tiên, mặc định 30s nếu không đọc được
static uint32_t parseAnswerTtl(const std::vector<uint8_t>& resp) {
    if (resp.size() < 12) return 30;
    uint16_t ancount = (resp[6] << 8) | resp[7];
    if (ancount == 0) return 30;
    size_t pos = 12;
    // bỏ qua Question section
    while (pos < resp.size() && resp[pos] != 0) {
        if (resp[pos] & 0xC0) { pos += 2; break; }
        pos += resp[pos] + 1;
    }
    if (pos < resp.size() && resp[pos] == 0) pos += 1;
    pos += 4; // QTYPE + QCLASS
    if (pos + 10 > resp.size()) return 30;
    // NAME (thường là pointer 2 byte) + TYPE(2) + CLASS(2) + TTL(4)
    if (resp[pos] & 0xC0) pos += 2; else return 30;
    pos += 4; // TYPE + CLASS
    if (pos + 4 > resp.size()) return 30;
    uint32_t ttl = (resp[pos] << 24) | (resp[pos + 1] << 16) | (resp[pos + 2] << 8) | resp[pos + 3];
    if (ttl == 0 || ttl > 3600) ttl = 30; // an toàn: chặn TTL vô lý (0 hoặc quá lớn)
    return ttl;
}

void sendDnsResponse(int tun_fd, uint32_t saddr, uint32_t daddr, uint16_t sport, uint16_t dport, const std::vector<uint8_t>& resp_data) {
    size_t total_len = sizeof(struct iphdr) + 8 + resp_data.size();
    std::vector<uint8_t> out_pkt(total_len, 0);

    struct iphdr* iph = (struct iphdr*)out_pkt.data();
    struct udphdr* udph = (struct udphdr*)(out_pkt.data() + sizeof(struct iphdr));
    uint8_t* data = out_pkt.data() + sizeof(struct iphdr) + 8;

    memcpy(data, resp_data.data(), resp_data.size());

    iph->ihl = 5;
    iph->version = 4;
    iph->tos = 0;
    iph->tot_len = htons(total_len);
    iph->id = htons(54321);
    iph->frag_off = 0;
    iph->ttl = 64;
    iph->protocol = IPPROTO_UDP;
    iph->saddr = daddr;
    iph->daddr = saddr;
    iph->check = 0;
    iph->check = csum((uint16_t*)out_pkt.data(), iph->ihl * 4);

    udph->uh_sport = htons(dport);
    udph->uh_dport = htons(sport);
    udph->uh_ulen = htons(8 + resp_data.size());
    udph->uh_sum = 0;

    write(tun_fd, out_pkt.data(), total_len);
}

void performDnsRace(int tun_fd, uint32_t saddr, uint32_t daddr, uint16_t sport, uint16_t dport, std::vector<uint8_t> payload, std::string queryKey) {
    // FIX #3: chặn nếu đã đạt giới hạn luồng đồng thời (bảo vệ CPU/pin của máy)
    if (gActiveRaces.fetch_add(1) >= kMaxConcurrentRaces) {
        gActiveRaces.fetch_sub(1);
        return;
    }

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        gActiveRaces.fetch_sub(1);
        return;
    }

    // FIX #2: protect socket ngay sau khi tạo, trước khi gửi bất kỳ gói nào ra ngoài
    protectNativeSocket(sock);

    int optval = 1024 * 1024 * 2;
    setsockopt(sock, SOL_SOCKET, SO_RCVBUF, &optval, sizeof(optval));
    setsockopt(sock, SOL_SOCKET, SO_SNDBUF, &optval, sizeof(optval));

    // FIX: giảm timeout để fallback nhanh hơn khi cả 4 server đều không phản hồi
    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 600000; // 600ms thay vì 1.2s

    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    const char* servers[] = {
        "8.8.8.8", "8.8.4.4",
        "1.1.1.1", "1.0.0.1",
    };

    for (const char* ip : servers) {
        struct sockaddr_in dest{};
        dest.sin_family = AF_INET;
        dest.sin_port = htons(53);
        inet_pton(AF_INET, ip, &dest.sin_addr);
        sendto(sock, payload.data(), payload.size(), 0, (struct sockaddr*)&dest, sizeof(dest));
    }

    uint8_t resp_buf[2048];
    struct sockaddr_in from{};
    socklen_t fromlen = sizeof(from);

    ssize_t res_len = recvfrom(sock, resp_buf, sizeof(resp_buf), 0, (struct sockaddr*)&from, &fromlen);
    close(sock);
    gActiveRaces.fetch_sub(1);

    if (res_len > 0) {
        std::vector<uint8_t> resp_data(resp_buf, resp_buf + res_len);

        // FIX #4: chỉ cache phản hồi thành công, kèm TTL thật, không cache lỗi/SERVFAIL
        if (isSuccessResponse(resp_data)) {
            uint32_t ttlSec = parseAnswerTtl(resp_data);
            std::unique_lock<std::shared_mutex> lock(gCacheMutex);
            gDnsCache[queryKey] = CacheEntry{resp_data, nowMs() + (int64_t)ttlSec * 1000};
        }

        sendDnsResponse(tun_fd, saddr, daddr, sport, dport, resp_data);
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

    // Cố gắng nâng độ ưu tiên luồng xử lý mạng - KIỂM TRA kết quả thay vì âm thầm bỏ qua
    if (setpriority(PRIO_PROCESS, 0, -10) != 0) {
        LOGI("setpriority thất bại (bình thường trên nhiều thiết bị) - engine vẫn hoạt động ở mức ưu tiên mặc định");
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

    // FIX #10: throttle callback thống kê - chỉ gọi JNI tối đa mỗi 250ms
    int64_t lastStatsPush = nowMs();

    LOGI("Bat dau vong lap DNS Optimization Engine...");

    while (gIsRunning) {
        int ret = poll(&pfd, 1, 100);
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

                        uint16_t dest_port = ntohs(udph->uh_dport);
                        if (dest_port == 53) {
                            uint8_t* dns_payload = buffer + iph_len + 8;
                            size_t dns_len = length - iph_len - 8;

                            // FIX #1: chặn quảng cáo/tracker THẬT dựa trên tên miền, không đoán ngẫu nhiên
                            std::string domain = parseQName(dns_payload, dns_len);
                            if (isAdTrackerDomain(domain)) {
                                adsBlocked++;
                                continue;
                            }

                            // FIX #5: khoá cache = toàn bộ header 12 byte bị loại bỏ (chỉ giữ Question)
                            if (dns_len > 12) {
                                std::string queryKey((char*)dns_payload + 12, dns_len - 12);
                                bool cacheHit = false;
                                std::vector<uint8_t> cachedResp;

                                {
                                    std::shared_lock<std::shared_mutex> lock(gCacheMutex);
                                    auto it = gDnsCache.find(queryKey);
                                    if (it != gDnsCache.end() && it->second.expiresAtMs > nowMs()) {
                                        cachedResp = it->second.data;
                                        cacheHit = true;
                                    }
                                }

                                uint32_t saddr = iph->saddr;
                                uint32_t daddr = iph->daddr;
                                uint16_t sport = ntohs(udph->uh_sport);
                                uint16_t dport = dest_port;

                                if (cacheHit && cachedResp.size() > 2) {
                                    cachedResp[0] = dns_payload[0];
                                    cachedResp[1] = dns_payload[1];
                                    sendDnsResponse(fd, saddr, daddr, sport, dport, cachedResp);
                                    continue;
                                }

                                std::vector<uint8_t> payload_copy(dns_payload, dns_payload + dns_len);
                                std::thread race_thread(performDnsRace, fd, saddr, daddr, sport, dport, payload_copy, queryKey);
                                race_thread.detach();
                            }
                        }
                    }
                }
            }
        }

        int64_t t = nowMs();
        if (updateStatsMethod != nullptr && gIsRunning && (t - lastStatsPush) >= 250) {
            env->CallStaticVoidMethod(gEngineClass, updateStatsMethod, (jlong)packets, (jlong)totalBytes, (jlong)adsBlocked);
            lastStatsPush = t;
        }
    }

    if (attached) gJvm->DetachCurrentThread();
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_networkoptimizer_vpn_NativePacketEngine_stringFromJNI(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF("NETWORK OPTIMIZER ENGINE ACTIVE");
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
    {
        std::unique_lock<std::shared_mutex> lock(gCacheMutex);
        gDnsCache.clear();
    }
    if (gEngineClass != nullptr) {
        env->DeleteGlobalRef(gEngineClass);
        gEngineClass = nullptr;
    }
}
