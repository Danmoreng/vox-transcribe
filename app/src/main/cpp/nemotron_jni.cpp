#include <jni.h>

#include <chrono>
#include <exception>
#include <memory>
#include <string>
#include <utility>

#include "ort_genai.h"

namespace {

struct ModelHandle {
    explicit ModelHandle(std::string model_path)
        : path(std::move(model_path)),
          config(OgaConfig::Create(path.c_str())),
          model(OgaModel::Create(*config)),
          tokenizer(OgaTokenizer::Create(*model)) {}

    std::string path;
    std::unique_ptr<OgaConfig> config;
    std::unique_ptr<OgaModel> model;
    std::unique_ptr<OgaTokenizer> tokenizer;
};

struct StreamHandle {
    StreamHandle(ModelHandle& owner, const std::string& language_id)
        : model_handle(owner),
          processor(OgaStreamingProcessor::Create(*owner.model)),
          tokenizer_stream(OgaTokenizerStream::Create(*owner.tokenizer)),
          params(OgaGeneratorParams::Create(*owner.model)),
          generator(OgaGenerator::Create(*owner.model, *params)) {
        processor->SetOption("use_vad", "false");
        generator->SetRuntimeOption("lang_id", language_id.c_str());
    }

    ModelHandle& model_handle;
    std::unique_ptr<OgaStreamingProcessor> processor;
    std::unique_ptr<OgaTokenizerStream> tokenizer_stream;
    std::unique_ptr<OgaGeneratorParams> params;
    std::unique_ptr<OgaGenerator> generator;
};

std::string DecodeTokens(OgaGenerator& generator, OgaTokenizerStream& tokenizer_stream) {
    std::string text;
    while (!generator.IsDone()) {
        generator.GenerateNextToken();
        const auto next_tokens = generator.GetNextTokens();
        if (!next_tokens.empty()) {
            const char* token_text = tokenizer_stream.Decode(next_tokens[0]);
            if (token_text != nullptr) {
                text += token_text;
            }
        }
    }
    return text;
}

std::string ProcessInputs(StreamHandle& stream, std::unique_ptr<OgaNamedTensors> inputs) {
    if (!inputs) {
        return "";
    }
    stream.generator->SetInputs(*inputs);
    return DecodeTokens(*stream.generator, *stream.tokenizer_stream);
}

std::string Transcribe(
    const std::string& model_path,
    const float* samples,
    size_t sample_count,
    const std::string& language_id
) {
    auto config = OgaConfig::Create(model_path.c_str());
    auto model = OgaModel::Create(*config);
    auto processor = OgaStreamingProcessor::Create(*model);
    processor->SetOption("use_vad", "false");

    auto tokenizer = OgaTokenizer::Create(*model);
    auto tokenizer_stream = OgaTokenizerStream::Create(*tokenizer);
    auto params = OgaGeneratorParams::Create(*model);
    auto generator = OgaGenerator::Create(*model, *params);
    generator->SetRuntimeOption("lang_id", language_id.c_str());

    constexpr size_t kChunkSamples = 8960;
    std::string transcript;
    for (size_t offset = 0; offset < sample_count; offset += kChunkSamples) {
        const size_t count = std::min(kChunkSamples, sample_count - offset);
        auto inputs = processor->Process(samples + offset, count);
        if (inputs) {
            generator->SetInputs(*inputs);
            transcript += DecodeTokens(*generator, *tokenizer_stream);
        }
    }

    auto inputs = processor->Flush();
    if (inputs) {
        generator->SetInputs(*inputs);
        transcript += DecodeTokens(*generator, *tokenizer_stream);
    }
    return transcript;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_voxtranscribe_data_nemotron_NemotronNative_loadModel(
    JNIEnv* env,
    jobject,
    jstring model_path
) {
    const char* path_chars = env->GetStringUTFChars(model_path, nullptr);
    try {
        auto* handle = new ModelHandle(path_chars);
        env->ReleaseStringUTFChars(model_path, path_chars);
        return reinterpret_cast<jlong>(handle);
    } catch (const std::exception& error) {
        env->ReleaseStringUTFChars(model_path, path_chars);
        jclass exception_class = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exception_class, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_voxtranscribe_data_nemotron_NemotronNative_freeModel(
    JNIEnv*,
    jobject,
    jlong handle
) {
    delete reinterpret_cast<ModelHandle*>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_voxtranscribe_data_nemotron_NemotronNative_beginStream(
    JNIEnv* env,
    jobject,
    jlong model_handle,
    jint language_id
) {
    try {
        auto* model = reinterpret_cast<ModelHandle*>(model_handle);
        if (model == nullptr) {
            throw std::runtime_error("Nemotron model is not loaded.");
        }
        auto* stream = new StreamHandle(*model, std::to_string(language_id));
        return reinterpret_cast<jlong>(stream);
    } catch (const std::exception& error) {
        jclass exception_class = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exception_class, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voxtranscribe_data_nemotron_NemotronNative_feedStream(
    JNIEnv* env,
    jobject,
    jlong stream_handle,
    jfloatArray audio
) {
    jfloat* samples = env->GetFloatArrayElements(audio, nullptr);
    try {
        auto* stream = reinterpret_cast<StreamHandle*>(stream_handle);
        if (stream == nullptr) {
            throw std::runtime_error("Nemotron stream is not active.");
        }
        const std::string result = ProcessInputs(
            *stream,
            stream->processor->Process(
                samples,
                static_cast<size_t>(env->GetArrayLength(audio))
            )
        );
        env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& error) {
        env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
        jclass exception_class = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exception_class, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voxtranscribe_data_nemotron_NemotronNative_finalizeStream(
    JNIEnv* env,
    jobject,
    jlong stream_handle
) {
    try {
        auto* stream = reinterpret_cast<StreamHandle*>(stream_handle);
        if (stream == nullptr) {
            throw std::runtime_error("Nemotron stream is not active.");
        }
        const std::string result = ProcessInputs(*stream, stream->processor->Flush());
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& error) {
        jclass exception_class = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exception_class, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_voxtranscribe_data_nemotron_NemotronNative_freeStream(
    JNIEnv*,
    jobject,
    jlong handle
) {
    delete reinterpret_cast<StreamHandle*>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voxtranscribe_data_nemotron_NemotronNative_transcribe(
    JNIEnv* env,
    jobject,
    jstring model_path,
    jfloatArray audio,
    jint language_id
) {
    const char* path_chars = env->GetStringUTFChars(model_path, nullptr);
    jfloat* samples = env->GetFloatArrayElements(audio, nullptr);
    try {
        const std::string result = Transcribe(
            path_chars,
            samples,
            static_cast<size_t>(env->GetArrayLength(audio)),
            std::to_string(language_id)
        );
        env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
        env->ReleaseStringUTFChars(model_path, path_chars);
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& error) {
        env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
        env->ReleaseStringUTFChars(model_path, path_chars);
        jclass exception_class = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exception_class, error.what());
        return nullptr;
    }
}
