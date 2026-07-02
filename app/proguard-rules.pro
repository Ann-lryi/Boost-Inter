# File này được app/build.gradle.kts tham chiếu (proguardFiles) ở buildType
# release nhưng KHÔNG tồn tại trong project gốc — assembleRelease/bundleRelease
# chắc chắn lỗi cấu hình vì thiếu file. Tạo file với rule tối thiểu cần thiết.

# Giữ nguyên tên method JNI native — bắt buộc để hàm C++ (System.loadLibrary +
# external fun) tìm đúng symbol sau khi minify, nếu không sẽ crash
# UnsatisfiedLinkError ngay khi gọi startPacketProcessing/stopPacketProcessing.
-keepclasseswithmembernames class com.networkoptimizer.vpn.NativePacketEngine {
    native <methods>;
}

# Các hàm static được gọi ngược từ C++ qua JNI (CallStaticVoidMethod) không
# được ProGuard/R8 tự biết là "đang được dùng" (không có caller Kotlin/Java
# nào rõ ràng) nên có thể bị coi là dead code và xoá/đổi tên nếu minify.
-keepclassmembers class com.networkoptimizer.vpn.NativePacketEngine {
    public static *** onTrafficUpdated(...);
    public static *** protect(...);
}
