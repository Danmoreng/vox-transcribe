cmake_minimum_required(VERSION 3.18)

project(vox_transcribe_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ORT_GENAI_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../models/onnxruntime-genai-android-0.14.0")

add_library(onnxruntime_genai SHARED IMPORTED)
set_target_properties(
    onnxruntime_genai
    PROPERTIES
        IMPORTED_LOCATION "${ORT_GENAI_ROOT}/jni/${ANDROID_ABI}/libonnxruntime-genai.so"
)

add_library(vox_nemotron_jni SHARED nemotron_jni.cpp)
target_include_directories(
    vox_nemotron_jni
    PRIVATE
        "${ORT_GENAI_ROOT}/headers"
)
target_link_libraries(
    vox_nemotron_jni
    PRIVATE
        onnxruntime_genai
        log
)
