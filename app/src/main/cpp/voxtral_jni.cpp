#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "voxtral.h"

#define TAG "VoxtralJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct VoxtralHandle {
    voxtral_model *model;
    voxtral_context *ctx;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_voxtranscribe_data_VoxtralJni_init(JNIEnv *env, jobject thiz, jstring modelPath, jint threads, jint gpuBackend, jint kvWindow) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    std::string pathStr(path);
    env->ReleaseStringUTFChars(modelPath, path);

    // Lambda for logging
    auto logger = [](voxtral_log_level level, const std::string & msg) {
        if (level == voxtral_log_level::error) {
            LOGE("Voxtral: %s", msg.c_str());
        } else {
            LOGI("Voxtral: %s", msg.c_str());
        }
    };

    voxtral_gpu_backend backend = static_cast<voxtral_gpu_backend>(gpuBackend);

    LOGI("Loading model from %s (GPU backend: %d, KV window: %d)", pathStr.c_str(), gpuBackend, kvWindow);
    voxtral_model *model = voxtral_model_load_from_file(pathStr, logger, backend);
    if (!model) {
        LOGE("Failed to load model from %s", pathStr.c_str());
        return 0;
    }

    voxtral_context_params params;
    params.n_threads = threads;
    params.kv_window_override = kvWindow;
    params.log_level = voxtral_log_level::info;
    params.logger = logger;
    params.gpu = backend;

    voxtral_context *ctx = voxtral_init_from_model(model, params);
    if (!ctx) {
        LOGE("Failed to create context");
        voxtral_model_free(model);
        return 0;
    }

    VoxtralHandle *handle = new VoxtralHandle();
    handle->model = model;
    handle->ctx = ctx;
    
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_com_example_voxtranscribe_data_VoxtralJni_free(JNIEnv *env, jobject thiz, jlong handlePtr) {
    if (handlePtr == 0) return;
    VoxtralHandle *handle = reinterpret_cast<VoxtralHandle *>(handlePtr);
    
    if (handle->ctx) {
        voxtral_free(handle->ctx);
        handle->ctx = nullptr;
    }
    if (handle->model) {
        voxtral_model_free(handle->model);
        handle->model = nullptr;
    }
    delete handle;
}

JNIEXPORT jstring JNICALL
Java_com_example_voxtranscribe_data_VoxtralJni_transcribe(JNIEnv *env, jobject thiz, jlong handlePtr, jfloatArray audioData, jint maxTokens) {
    if (handlePtr == 0) return env->NewStringUTF("");
    VoxtralHandle *handle = reinterpret_cast<VoxtralHandle *>(handlePtr);
    if (!handle->ctx) return env->NewStringUTF("");

    jsize len = env->GetArrayLength(audioData);
    jfloat *body = env->GetFloatArrayElements(audioData, 0);
    
    std::vector<float> audio;
    audio.reserve(len);
    for (int i = 0; i < len; i++) {
        audio.push_back(body[i]);
    }
    
    env->ReleaseFloatArrayElements(audioData, body, 0);

    voxtral_result result;
    // Note: voxtral_transcribe_audio is the one-shot API.
    bool success = voxtral_transcribe_audio(*handle->ctx, audio, maxTokens, result);
    
    if (!success) {
        LOGE("Transcription failed");
        return env->NewStringUTF("");
    }
    
    return env->NewStringUTF(result.text.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_example_voxtranscribe_data_VoxtralJni_streamInit(JNIEnv *env, jobject thiz, jlong ctxPtr) {
    if (ctxPtr == 0) return 0;
    VoxtralHandle *handle = reinterpret_cast<VoxtralHandle *>(ctxPtr);
    
    voxtral_stream_params params; // use defaults
    voxtral_stream *stream = voxtral_stream_create(handle->ctx, params);
    
    if (!stream) {
        LOGE("Failed to create stream");
        return 0;
    }
    
    return reinterpret_cast<jlong>(stream);
}

JNIEXPORT void JNICALL
Java_com_example_voxtranscribe_data_VoxtralJni_streamFree(JNIEnv *env, jobject thiz, jlong streamPtr) {
    if (streamPtr == 0) return;
    voxtral_stream *stream = reinterpret_cast<voxtral_stream *>(streamPtr);
    voxtral_stream_free(stream);
}

JNIEXPORT jboolean JNICALL
Java_com_example_voxtranscribe_data_VoxtralJni_streamPush(JNIEnv *env, jobject thiz, jlong streamPtr, jfloatArray audioData) {
    if (streamPtr == 0) return false;
    voxtral_stream *stream = reinterpret_cast<voxtral_stream *>(streamPtr);
    
    jsize len = env->GetArrayLength(audioData);
    jfloat *body = env->GetFloatArrayElements(audioData, 0);
    
    bool success = voxtral_stream_push_pcm(stream, body, len);
    
    env->ReleaseFloatArrayElements(audioData, body, 0);
    return success;
}

JNIEXPORT jstring JNICALL
Java_com_example_voxtranscribe_data_VoxtralJni_streamDecode(JNIEnv *env, jobject thiz, jlong streamPtr) {
    if (streamPtr == 0) return env->NewStringUTF("");
    voxtral_stream *stream = reinterpret_cast<voxtral_stream *>(streamPtr);
    
    voxtral_result result;
    if (voxtral_stream_decode(stream, result)) {
        return env->NewStringUTF(result.text.c_str());
    }
    return env->NewStringUTF("");
}

JNIEXPORT jstring JNICALL
Java_com_example_voxtranscribe_data_VoxtralJni_streamFlush(JNIEnv *env, jobject thiz, jlong streamPtr) {
    if (streamPtr == 0) return env->NewStringUTF("");
    voxtral_stream *stream = reinterpret_cast<voxtral_stream *>(streamPtr);
    
    voxtral_result result;
    if (voxtral_stream_flush(stream, result)) {
        return env->NewStringUTF(result.text.c_str());
    }
    return env->NewStringUTF("");
}

} // extern "C"
