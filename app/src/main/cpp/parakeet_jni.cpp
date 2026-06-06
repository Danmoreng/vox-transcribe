#include <jni.h>

#include <algorithm>
#include <string>

#include "backend.hpp"
#include "ggml_graph.hpp"
#include "parakeet_capi.h"

namespace {

struct VoxParakeetStream {
    parakeet_stream* stream = nullptr;
    int last_eou = 0;
};

std::string jstring_to_string(JNIEnv* env, jstring value) {
    if (!value) {
        return "";
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) {
        return "";
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring string_to_jstring(JNIEnv* env, const char* value) {
    return env->NewStringUTF(value ? value : "");
}

parakeet_ctx* as_ctx(jlong handle) {
    return reinterpret_cast<parakeet_ctx*>(handle);
}

VoxParakeetStream* as_stream(jlong handle) {
    return reinterpret_cast<VoxParakeetStream*>(handle);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_example_voxtranscribe_data_parakeet_ParakeetNative_abiVersion(
    JNIEnv*, jobject) {
    return parakeet_capi_abi_version();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_voxtranscribe_data_parakeet_ParakeetNative_setThreadCount(
    JNIEnv*, jobject, jint thread_count) {
    pk::set_num_threads(std::max(1, static_cast<int>(thread_count)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voxtranscribe_data_parakeet_ParakeetNative_activeBackendName(
    JNIEnv* env, jobject) {
    return string_to_jstring(env, pk::global_backend().device_name());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_voxtranscribe_data_parakeet_ParakeetNative_loadModel(
    JNIEnv* env, jobject, jstring model_path) {
    const std::string path = jstring_to_string(env, model_path);
    return reinterpret_cast<jlong>(parakeet_capi_load(path.c_str()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_voxtranscribe_data_parakeet_ParakeetNative_freeModel(
    JNIEnv*, jobject, jlong handle) {
    parakeet_capi_free(as_ctx(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voxtranscribe_data_parakeet_ParakeetNative_lastError(
    JNIEnv* env, jobject, jlong handle) {
    return string_to_jstring(env, parakeet_capi_last_error(as_ctx(handle)));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_voxtranscribe_data_parakeet_ParakeetNative_beginStream(
    JNIEnv* env, jobject, jlong model_handle, jstring target_lang) {
    parakeet_ctx* ctx = as_ctx(model_handle);
    if (!ctx) {
        return 0;
    }

    const std::string lang = jstring_to_string(env, target_lang);
    parakeet_stream* stream = parakeet_capi_stream_begin_lang(
        ctx,
        lang.empty() ? nullptr : lang.c_str());
    if (!stream) {
        return 0;
    }

    auto* wrapper = new VoxParakeetStream();
    wrapper->stream = stream;
    return reinterpret_cast<jlong>(wrapper);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voxtranscribe_data_parakeet_ParakeetNative_feedStream(
    JNIEnv* env, jobject, jlong stream_handle, jfloatArray samples) {
    VoxParakeetStream* wrapper = as_stream(stream_handle);
    if (!wrapper || !wrapper->stream || !samples) {
        return nullptr;
    }

    const jsize sample_count = env->GetArrayLength(samples);
    if (sample_count <= 0) {
        wrapper->last_eou = 0;
        return env->NewStringUTF("");
    }

    jboolean is_copy = JNI_FALSE;
    jfloat* sample_data = env->GetFloatArrayElements(samples, &is_copy);
    if (!sample_data) {
        return nullptr;
    }

    int eou = 0;
    char* text = parakeet_capi_stream_feed(
        wrapper->stream,
        sample_data,
        static_cast<int>(sample_count),
        &eou);
    env->ReleaseFloatArrayElements(samples, sample_data, JNI_ABORT);

    wrapper->last_eou = eou;
    if (!text) {
        return nullptr;
    }

    jstring result = env->NewStringUTF(text);
    parakeet_capi_free_string(text);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_voxtranscribe_data_parakeet_ParakeetNative_lastFeedHadEou(
    JNIEnv*, jobject, jlong stream_handle) {
    VoxParakeetStream* wrapper = as_stream(stream_handle);
    return wrapper && wrapper->last_eou ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voxtranscribe_data_parakeet_ParakeetNative_finalizeStream(
    JNIEnv* env, jobject, jlong stream_handle) {
    VoxParakeetStream* wrapper = as_stream(stream_handle);
    if (!wrapper || !wrapper->stream) {
        return nullptr;
    }

    char* text = parakeet_capi_stream_finalize(wrapper->stream);
    if (!text) {
        return nullptr;
    }

    jstring result = env->NewStringUTF(text);
    parakeet_capi_free_string(text);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_voxtranscribe_data_parakeet_ParakeetNative_freeStream(
    JNIEnv*, jobject, jlong stream_handle) {
    VoxParakeetStream* wrapper = as_stream(stream_handle);
    if (!wrapper) {
        return;
    }
    parakeet_capi_stream_free(wrapper->stream);
    delete wrapper;
}
