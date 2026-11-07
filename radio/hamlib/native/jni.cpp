#include "adapter.hpp"
#include <jni.h>
#include <exception>

namespace {
void fail(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_net_vash_ft8cbdroid_hamlib_NativeHamlib_version(JNIEnv* env, jobject) {
    try { return env->NewStringUTF(cbcat::version().c_str()); }
    catch (const std::exception& e) { fail(env, e); return nullptr; }
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_net_vash_ft8cbdroid_hamlib_NativeHamlib_models(JNIEnv* env, jobject) {
    try {
        const auto models = cbcat::catalog();
        jclass cls = env->FindClass("net/vash/ft8cbdroid/core/radio/RadioModel");
        if (!cls) return nullptr;
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIZZZ)V");
        if (!ctor) return nullptr;
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(models.size()), cls, nullptr);
        if (!array) return nullptr;
        for (size_t i = 0; i < models.size(); ++i) {
            const auto& m = models[i];
            if (env->PushLocalFrame(4) != JNI_OK) return nullptr;
            jstring manufacturer = env->NewStringUTF(m.manufacturer.c_str());
            jstring name = env->NewStringUTF(m.name.c_str());
            jstring status = env->NewStringUTF(m.status.c_str());
            if (env->ExceptionCheck()) { env->PopLocalFrame(nullptr); return nullptr; }
            jobject row = env->NewObject(cls, ctor, m.id, manufacturer, name, status,
                m.port, m.baud_min, m.baud_max, static_cast<jboolean>(m.read_frequency),
                static_cast<jboolean>(m.read_mode), static_cast<jboolean>(m.read_ptt));
            if (!env->ExceptionCheck()) env->SetObjectArrayElement(array, static_cast<jsize>(i), row);
            env->PopLocalFrame(nullptr);
            if (env->ExceptionCheck()) return nullptr;
        }
        return array;
    } catch (const std::exception& e) { fail(env, e); return nullptr; }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_net_vash_ft8cbdroid_hamlib_NativeHamlib_dummy(JNIEnv* env, jobject) {
    try {
        const auto result = cbcat::check_dummy();
        const jlong values[] = {result.frequency, result.usb ? 1 : 0, result.ptt_off ? 0 : 1};
        jlongArray array = env->NewLongArray(3);
        if (array) env->SetLongArrayRegion(array, 0, 3, values);
        return array;
    } catch (const std::exception& e) { fail(env, e); return nullptr; }
}
