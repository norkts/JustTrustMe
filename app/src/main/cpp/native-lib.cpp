#include <jni.h>
#include <dlfcn.h>
#include "include/dobby.h"
#include <android/log.h>

#define LOG_TAG "FlutterSSLHook"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// 原始函数指针
static int (*original_X509_verify_cert)(void *ctx) = nullptr;

// Hook 后的函数（强制返回验证成功）
int fake_X509_verify_cert(void *ctx) {
    LOGD("Bypassing X509_verify_cert()");
    return 1;
}

extern "C" JNIEXPORT void JNICALL
Java_just_trust_me_FlutterSSLHook_init(JNIEnv *env, jclass clazz) {
    // 加载 Flutter 的 BoringSSL 库
    void *handle = dlopen("libflutter.so", RTLD_LAZY);
    if (!handle) {
        LOGD("Error: libflutter.so not loaded");
        return;
    }

    // 获取目标函数地址（需根据实际符号调整）
    void *target_func = dlsym(handle, "X509_verify_cert");
    if (!target_func) {
        LOGD("Error: X509_verify_cert() not found");
        return;
    }

    // 使用 Dobby 进行 Hook
    DobbyHook(target_func, (void *)fake_X509_verify_cert, (void **)&original_X509_verify_cert);
    LOGD("Hook X509_verify_cert() success");
}