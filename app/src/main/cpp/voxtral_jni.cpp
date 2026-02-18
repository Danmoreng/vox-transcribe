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
Java_com_example_voxtranscribe_data_VoxtralJni_init(JNIEnv *env, jobject thiz, jstring modelPath, jint threads) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    std::string pathStr(path);
    env->ReleaseStringUTFChars(modelPath, path);

    // Lambda for logging
    // Note: We need a way to pass this lambda to C function pointer if expected, 
    // but voxtral_log_callback is std::function, so capturing lambda works.
    auto logger = [](voxtral_log_level level, const std::string & msg) {
        if (level == voxtral_log_level::error) {
            LOGE("Voxtral: %s", msg.c_str());
        } else {
            LOGI("Voxtral: %s", msg.c_str());
        }
    };

    LOGI("Loading model from %s", pathStr.c_str());
    voxtral_model *model = voxtral_model_load_from_file(pathStr, logger, voxtral_gpu_backend::none);
    if (!model) {
        LOGE("Failed to load model from %s", pathStr.c_str());
        return 0;
    }

    voxtral_context_params params;
    params.n_threads = threads;
    params.log_level = voxtral_log_level::info;
    params.logger = logger;
    params.gpu = voxtral_gpu_backend::none;

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

} // extern "C"
