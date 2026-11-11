#include "engine.hpp"
#include <jni.h>
#include <stdexcept>
#include <atomic>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace {
struct Call { std::atomic<bool> cancelled{false}; std::atomic<int> stage{0}; };
std::mutex calls_mutex;
std::unordered_map<jlong, std::shared_ptr<Call>> calls;
jlong next_call=1;
std::shared_ptr<Call> call_for(jlong id) {
    std::lock_guard<std::mutex> lock(calls_mutex);
    auto it=calls.find(id);
    if (it==calls.end()) throw std::invalid_argument("Unknown native request");
    return it->second;
}
eightcb::StopRequested cancellation(std::shared_ptr<Call> call) {
    return [call](eightcb::WorkStage stage) {
        call->stage.store(static_cast<int>(stage));
        return call->cancelled.load();
    };
}
eightcb::Payload payload(JNIEnv* env, jbyteArray bytes) {
    if (!bytes || env->GetArrayLength(bytes)!=10) throw std::invalid_argument("Expected 10 payload bytes");
    eightcb::Payload p{};
    env->GetByteArrayRegion(bytes,0,10,reinterpret_cast<jbyte*>(p.data()));
    return p;
}
void failure(JNIEnv* env, std::exception const& error) {
    char const* type="java/lang/IllegalArgumentException";
    if (dynamic_cast<std::bad_alloc const*>(&error)) type="java/lang/OutOfMemoryError";
    if (dynamic_cast<eightcb::WorkCancelled const*>(&error)) type="java/util/concurrent/CancellationException";
    if (dynamic_cast<eightcb::WorkExpired const*>(&error)) type="net/vash/ft8cbdroid/core/Ft8EngineTimeoutException";
    env->ThrowNew(env->FindClass(type), error.what());
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_net_vash_ft8cbdroid_dsp_NativeBridge_createCall(JNIEnv* env, jobject) {
    try {
        std::lock_guard<std::mutex> lock(calls_mutex);
        jlong id=next_call++;
        calls.emplace(id,std::make_shared<Call>());
        return id;
    } catch (std::exception const& error) { failure(env,error); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_net_vash_ft8cbdroid_dsp_NativeBridge_cancelCall(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(calls_mutex);
    auto it=calls.find(id);
    if(it!=calls.end()) it->second->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_net_vash_ft8cbdroid_dsp_NativeBridge_releaseCall(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(calls_mutex);
    calls.erase(id);
}
extern "C" JNIEXPORT jintArray JNICALL
Java_net_vash_ft8cbdroid_dsp_NativeBridge_workState(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(calls_mutex);
    jint state[2]={static_cast<int>(calls.size()), calls.empty()?-1:calls.begin()->second->stage.load()};
    auto out=env->NewIntArray(2);
    if (out) env->SetIntArrayRegion(out,0,2,state);
    return out;
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_net_vash_ft8cbdroid_dsp_NativeBridge_encode(JNIEnv* env, jobject, jbyteArray bytes, jfloat frequency, jfloat peak_amplitude, jlong id) {
    try {
        auto pcm=eightcb::encode_slot(payload(env,bytes),frequency,peak_amplitude,cancellation(call_for(id)));
        auto result=env->NewFloatArray(pcm.size());
        if (result) env->SetFloatArrayRegion(result,0,pcm.size(),pcm.data());
        return result;
    } catch (std::exception const& error) {failure(env,error);return nullptr;}
}
extern "C" JNIEXPORT jobject JNICALL
Java_net_vash_ft8cbdroid_dsp_NativeBridge_decode(JNIEnv* env, jobject, jfloatArray samples, jint profile, jlong id) {
    try {
        if (!samples || (env->GetArrayLength(samples)!=eightcb::slot_samples && env->GetArrayLength(samples)!=eightcb::early_window_samples))
            throw std::invalid_argument("Expected 13.5 or 15 seconds of actual 12 kHz PCM");
        std::vector<float> pcm(env->GetArrayLength(samples));
        env->GetFloatArrayRegion(samples,0,pcm.size(),pcm.data());
        auto decoded=eightcb::decode_slot(pcm,profile,cancellation(call_for(id)));
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
        return env->NewObject(type,env->GetMethodID(type,"<init>","([[B[F[F[I[FII)V"),
                              payloads,times,frequencies,scores,snrs,decoded.candidates,decoded.processed);
    } catch (std::exception const& error) {failure(env,error);return nullptr;}
}
