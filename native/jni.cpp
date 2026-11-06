#include "engine.hpp"
#include <jni.h>
#include <stdexcept>

namespace {
eightcb::Payload payload(JNIEnv* env, jbyteArray bytes) {
    if (!bytes || env->GetArrayLength(bytes)!=10) throw std::invalid_argument("Expected 10 payload bytes");
    eightcb::Payload p{};
    env->GetByteArrayRegion(bytes,0,10,reinterpret_cast<jbyte*>(p.data()));
    return p;
}
void failure(JNIEnv* env, std::exception const& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), error.what());
}
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_net_vash_ft8cbdroid_dsp_NativeBridge_encode(JNIEnv* env, jobject, jbyteArray bytes, jfloat frequency, jfloat peak_amplitude) {
    try {
        auto pcm=eightcb::encode_slot(payload(env,bytes),frequency,peak_amplitude);
        auto result=env->NewFloatArray(pcm.size());
        if (result) env->SetFloatArrayRegion(result,0,pcm.size(),pcm.data());
        return result;
    } catch (std::exception const& error) {failure(env,error);return nullptr;}
}
extern "C" JNIEXPORT jobject JNICALL
Java_net_vash_ft8cbdroid_dsp_NativeBridge_decode(JNIEnv* env, jobject, jfloatArray samples) {
    try {
        if (!samples || env->GetArrayLength(samples)!=eightcb::slot_samples)
            throw std::invalid_argument("Expected one 15-second 12 kHz PCM slot");
        std::vector<float> pcm(eightcb::slot_samples);
        env->GetFloatArrayRegion(samples,0,pcm.size(),pcm.data());
        auto decoded=eightcb::decode_slot(pcm);
        const int n=decoded.frames.size();
        auto payloads=env->NewObjectArray(n,env->FindClass("[B"),nullptr);
        auto times=env->NewFloatArray(n), frequencies=env->NewFloatArray(n);
        auto scores=env->NewIntArray(n);
        auto snrs=env->NewFloatArray(n);
        if (!payloads || !times || !frequencies || !scores || !snrs) return nullptr;
        for (int i=0;i<n;++i) {
            auto const& f=decoded.frames[i];
            auto bytes=env->NewByteArray(10);
            if (!bytes) return nullptr;
            env->SetByteArrayRegion(bytes,0,10,reinterpret_cast<jbyte const*>(f.payload.data()));
            env->SetObjectArrayElement(payloads,i,bytes);
            env->DeleteLocalRef(bytes);
            env->SetFloatArrayRegion(times,i,1,&f.time_seconds);
            env->SetFloatArrayRegion(frequencies,i,1,&f.frequency_hz);
            env->SetIntArrayRegion(scores,i,1,&f.sync_score);
            env->SetFloatArrayRegion(snrs,i,1,&f.estimated_snr_db);
        }
        auto type=env->FindClass("net/vash/ft8cbdroid/dsp/NativeResult");
        return env->NewObject(type,env->GetMethodID(type,"<init>","([[B[F[F[I[FI)V"),
                              payloads,times,frequencies,scores,snrs,decoded.candidates);
    } catch (std::exception const& error) {failure(env,error);return nullptr;}
}
