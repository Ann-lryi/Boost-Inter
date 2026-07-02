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
#include <shared_mutex>
#include <sys/resource.h>
#include <chrono>
#include <algorithm>

#define LOG_TAG "NativePacketProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JavaVM* gJvm = nullptr;
std::atomic<bool> gIsRunning(false);
std::thread gProcessorThread;
jclass gEngineClass = nullptr;

// Giới hạn số luồng race DNS chạy đồng thời. Không có giới hạn này, mỗi truy
// vấn DNS chưa cache (vd: mở 1 trang web có vài chục domain con) sẽ tạo 1 OS
// thread + 1 socket UDP mới không giới hạn, gây quá tải CPU/scheduler khi có
// burst truy vấn — đây là một nguyên nhân cụ thể gây "mạng chậm" khi dùng.
std::atomic<int> gInFlightRaces(0);
const int MAX_INFLIGHT_RACES = 48;

// --- BỘ NHỚ CACHE DNS (có TTL thật, không cache vĩnh viễn) ---
struct CacheEntry {
    std::vector<uint8_t> data;
    std::chrono::steady_clock::time_point expiresAt;
};
std::unordered_map<std::string, CacheEntry> gDnsCache;
std::shared_mutex gCacheMutex;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJvm = vm;
    return JNI_VERSION_1_6;
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

// Bỏ qua 1 tên miền DNS (chuỗi label hoặc compression pointer) bắt đầu tại pos.
// Trả về vị trí NGAY SAU tên đó, hoặc std::string::npos nếu dữ liệu không hợp lệ.
size_t skipDnsName(const std::vector<uint8_t>& resp, size_t pos) {
    if (pos >= resp.size()) return std::string::npos;
    if ((resp[pos] & 0xC0) == 0xC0) {
        if (pos + 2 > resp.size()) return std::string::npos;
        return pos + 2;
    }
    while (pos < resp.size() && resp[pos] != 0) {
        size_t labelLen = resp[pos];
        pos += 1 + labelLen;
        if (pos >= resp.size()) return std::string::npos;
    }
    return pos + 1;
}

// Trích TTL (giây) từ answer record đầu tiên trong DNS response thật, để cache
// hết hạn đúng thời gian thay vì lưu vĩnh viễn. Mọi bước đọc đều có kiểm tra
// biên trước — trả về giá trị mặc định an toàn nếu response dị dạng/quá ngắn,
// không bao giờ đọc ra ngoài vector.
uint32_t extractTtlSeconds(const std::vector<uint8_t>& resp) {
    const uint32_t DEFAULT_TTL = 30;
    const uint32_t MAX_TTL = 3600; // trần 1 giờ, tránh cache "kẹt" quá lâu nếu TTL bất thường

    if (resp.size() < 12) return DEFAULT_TTL;
    uint16_t qdcount = ((uint16_t)resp[4] << 8) | resp[5];
    uint16_t ancount = ((uint16_t)resp[6] << 8) | resp[7];
    if (ancount == 0 || qdcount == 0) return DEFAULT_TTL;

    size_t pos = 12;
    for (uint16_t q = 0; q < qdcount; q++) {
        pos = skipDnsName(resp, pos);
        if (pos == std::string::npos || pos + 4 > resp.size()) return DEFAULT_TTL;
        pos += 4; // QTYPE(2) + QCLASS(2)
    }

    pos = skipDnsName(resp, pos);
    if (pos == std::string::npos || pos + 8 > resp.size()) return DEFAULT_TTL;
    pos += 4; // TYPE(2) + CLASS(2)

    uint32_t ttl = ((uint32_t)resp[pos] << 24) | ((uint32_t)resp[pos + 1] << 16) |
                   ((uint32_t)resp[pos + 2] << 8) | (uint32_t)resp[pos + 3];

    if (ttl == 0) return DEFAULT_TTL; // TTL=0 = "đừng cache" theo RFC 1035, dùng fallback ngắn
    return std::min(ttl, MAX_TTL);
}

// LƯU Ý: bản gốc của hàm này là `(length > 40 && payload[length-1] % 10 == 0)`
// — không đọc tên miền, không so khớp danh sách chặn nào, chỉ dựa vào byte
// cuối của gói tin DNS. Với mọi truy vấn DNS dài hơn 40 byte (bao gồm phần
// lớn tên miền hợp lệ, không riêng quảng cáo), có ~10% xác suất bị coi là
// "quảng cáo" và bị drop im lặng — domain đó sẽ không bao giờ phân giải được
// cho tới khi VPN tắt/bật lại. Đây là nguyên nhân cụ thể có thể gây website
// không tải được ngẫu nhiên. Chặn quảng cáo thật cần danh sách domain chặn
// (vd: dựa theo QNAME thật trong gói tin) — không có trong phạm vi sửa lỗi
// này, nên tạm thời vô hiệu hoá thay vì giữ logic sai gây hại.
bool isAdTrackerDNS(const uint8_t* payload, size_t length) {
    (void)payload;
    (void)length;
    return false;
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
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        gInFlightRaces--; // phải giảm ở MỌI đường thoát, kể cả khi tạo socket thất bại —
                           // thiếu dòng này sẽ làm bộ đếm tăng dần không giảm, cuối cùng
                           // khoá toàn bộ DNS race vĩnh viễn (đúng loại lỗi đang sửa).
        return;
    }

    int optval = 1024 * 1024 * 2;
    setsockopt(sock, SOL_SOCKET, SO_RCVBUF, &optval, sizeof(optval));
    setsockopt(sock, SOL_SOCKET, SO_SNDBUF, &optval, sizeof(optval));

    struct timeval tv;
    tv.tv_sec = 1;
    tv.tv_usec = 200000;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    const char* servers[] = {
        "8.8.8.8", "8.8.4.4",
        "1.1.1.1", "1.0.0.1",
    };
    const int NUM_SERVERS = 4;
    struct in_addr serverAddrs[NUM_SERVERS];
    for (int i = 0; i < NUM_SERVERS; i++) {
        inet_pton(AF_INET, servers[i], &serverAddrs[i]);
    }

    for (int i = 0; i < NUM_SERVERS; i++) {
        struct sockaddr_in dest;
        dest.sin_family = AF_INET;
        dest.sin_port = htons(53);
        dest.sin_addr = serverAddrs[i];
        sendto(sock, payload.data(), payload.size(), 0, (struct sockaddr*)&dest, sizeof(dest));
    }

    uint8_t resp_buf[2048];
    struct sockaddr_in from;
    socklen_t fromlen = sizeof(from);

    ssize_t res_len = recvfrom(sock, resp_buf, sizeof(resp_buf), 0, (struct sockaddr*)&from, &fromlen);
    close(sock);

    if (res_len > 0) {
        // Chỉ chấp nhận phản hồi từ đúng 1 trong 4 server đã gửi truy vấn tới —
        // socket không "connect" nên có thể nhận gói UDP từ bất kỳ đâu; thiếu
        // kiểm tra này là kẽ hở cho phép giả mạo phản hồi DNS (cache poisoning)
        // nếu có bên thứ ba gửi gói UDP nguồn giả mạo tới đúng port kịp lúc.
        bool fromKnownServer = false;
        for (int i = 0; i < NUM_SERVERS; i++) {
            if (from.sin_addr.s_addr == serverAddrs[i].s_addr) {
                fromKnownServer = true;
                break;
            }
        }

        if (fromKnownServer) {
            std::vector<uint8_t> resp_data(resp_buf, resp_buf + res_len);
            uint32_t ttlSeconds = extractTtlSeconds(resp_data);
            auto expiry = std::chrono::steady_clock::now() + std::chrono::seconds(ttlSeconds);

            {
                std::unique_lock<std::shared_mutex> lock(gCacheMutex);
                gDnsCache[queryKey] = CacheEntry{resp_data, expiry};
            }

            sendDnsResponse(tun_fd, saddr, daddr, sport, dport, resp_data);
        }
    }

    gInFlightRaces--;
}

void processPacketsLoop(int fd) {
    JNIEnv* env;
    int getEnvStat = gJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    bool attached = false;
    if (getEnvStat == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(&env, NULL) == 0) attached = true;
        else return;
    }

    // Ưu tiên CPU cho luồng xử lý gói tin, nhưng KHÔNG dùng mức tối đa (-20 —
    // vốn dành cho tiến trình audio/realtime hệ thống). Mức -20 trên thiết bị
    // chưa root thường bị hệ thống từ chối một phần hoặc toàn bộ (yêu cầu
    // CAP_SYS_NICE), và bản gốc không kiểm tra kết quả trả về nên không biết
    // có thực sự áp dụng hay không. Nếu áp dụng được, mức -20 có thể khiến
    // luồng này giành CPU của toàn hệ thống, gây đơ máy tổng thể chứ không
    // riêng mạng chậm. Dùng mức vừa phải hơn (-8) và log lại kết quả thật.
    if (setpriority(PRIO_PROCESS, 0, -8) != 0) {
        LOGE("setpriority(-8) thất bại (errno=%d) — tiếp tục chạy với priority mặc định", errno);
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

    LOGI("Bat dau vong lap xu ly goi tin DNS...");

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

                        // Kiểm tra đủ độ dài TRƯỚC khi đọc UDP header / tính dns_len.
                        // Gói tin bị cắt/dị dạng có thể khiến length < iph_len+8,
                        // trước đây dns_len (size_t, unsigned) sẽ underflow thành số
                        // cực lớn, khiến isAdTrackerDNS đọc bộ nhớ ngoài phạm vi
                        // buffer -> crash native thread -> sập VPN interface ->
                        // toàn bộ thiết bị mất kết nối mạng cho tới khi bật lại VPN.
                        if ((size_t)length < iph_len + 8) {
                            continue;
                        }

                        struct udphdr* udph = (struct udphdr*)(buffer + iph_len);
                        uint16_t dest_port = ntohs(udph->uh_dport);
                        if (dest_port == 53) {
                            uint8_t* dns_payload = buffer + iph_len + 8;
                            size_t dns_len = (size_t)length - iph_len - 8;

                            if (dns_len < 12) {
                                // Ngắn hơn header DNS tối thiểu — không phải truy vấn hợp lệ.
                                continue;
                            }

                            if (isAdTrackerDNS(dns_payload, dns_len)) {
                                adsBlocked++;
                                continue;
                            }

                            std::string queryKey((char*)dns_payload + 2, dns_len - 2);
                            bool cacheHit = false;
                            std::vector<uint8_t> cachedResp;

                            {
                                std::shared_lock<std::shared_mutex> lock(gCacheMutex);
                                auto it = gDnsCache.find(queryKey);
                                if (it != gDnsCache.end() && it->second.expiresAt > std::chrono::steady_clock::now()) {
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
                            } else if (gInFlightRaces.load() < MAX_INFLIGHT_RACES) {
                                gInFlightRaces++;
                                std::vector<uint8_t> payload_copy(dns_payload, dns_payload + dns_len);
                                std::thread race_thread(performDnsRace, fd, saddr, daddr, sport, dport, payload_copy, queryKey);
                                race_thread.detach();
                            }
                            // Nếu vượt MAX_INFLIGHT_RACES: bỏ qua, không tạo thêm thread.
                            // Client sẽ tự retry theo timeout DNS resolver riêng của nó,
                            // giống hệt hành vi khi cả 4 server đều không phản hồi.
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
    return env->NewStringUTF("NETWORK OPTIMIZER ACTIVE");
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
