cmake_minimum_required(VERSION 3.18)

project(vox_transcribe_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(PARAKEET_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../external/parakeet.cpp")
set(SPIRV_HEADERS_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../external/SPIRV-Headers")
set(VULKAN_HEADERS_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../external/Vulkan-Headers")

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)

if(CMAKE_HOST_WIN32)
    file(TO_CMAKE_PATH "${CMAKE_MAKE_PROGRAM}" VOX_CMAKE_MAKE_PROGRAM)
    file(TO_CMAKE_PATH "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}" VOX_HOST_RUNTIME_OUTPUT_DIRECTORY)
    set(VOX_HOST_C_COMPILER "${CMAKE_CURRENT_LIST_DIR}/cmake/msvc-cl-wrapper.cmd")
    set(VOX_HOST_CXX_COMPILER "${CMAKE_CURRENT_LIST_DIR}/cmake/msvc-cl-wrapper.cmd")
    set(VOX_HOST_LINKER "C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64/link.exe")
    set(VOX_HOST_RC_COMPILER "C:/Program Files (x86)/Windows Kits/10/bin/10.0.26100.0/x64/rc.exe")
    set(VOX_HOST_MT "C:/Program Files (x86)/Windows Kits/10/bin/10.0.26100.0/x64/mt.exe")
    set(VOX_HOST_MSVC_LIB "C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC/14.44.35207/lib/x64")
    set(VOX_HOST_WINSDK_UM_LIB "C:/Program Files (x86)/Windows Kits/10/Lib/10.0.26100.0/um/x64")
    set(VOX_HOST_WINSDK_UCRT_LIB "C:/Program Files (x86)/Windows Kits/10/Lib/10.0.26100.0/ucrt/x64")
    configure_file(
        "${CMAKE_CURRENT_LIST_DIR}/cmake/vulkan-shaders-host-toolchain.cmake.in"
        "${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders-host-toolchain.cmake"
        @ONLY
    )
    set(
        GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
        "${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders-host-toolchain.cmake"
        CACHE FILEPATH "" FORCE
    )
endif()

set(SPIRV_HEADERS_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(SPIRV_HEADERS_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(
    "${SPIRV_HEADERS_ROOT}"
    "${CMAKE_CURRENT_BINARY_DIR}/spirv-headers"
)
set(SPIRV-Headers_DIR "${CMAKE_CURRENT_LIST_DIR}/cmake" CACHE PATH "" FORCE)
include_directories("${SPIRV_HEADERS_ROOT}/include")
include_directories("${VULKAN_HEADERS_ROOT}/include")

add_subdirectory(
    "${PARAKEET_ROOT}/third_party/ggml"
    "${CMAKE_CURRENT_BINARY_DIR}/ggml"
)

set(PARAKEET_SRC
    "${PARAKEET_ROOT}/src/parakeet.cpp"
    "${PARAKEET_ROOT}/src/model.cpp"
    "${PARAKEET_ROOT}/src/parakeet_capi.cpp"
    "${PARAKEET_ROOT}/src/common.cpp"
    "${PARAKEET_ROOT}/src/audio_io.cpp"
    "${PARAKEET_ROOT}/src/model_loader.cpp"
    "${PARAKEET_ROOT}/src/backend.cpp"
    "${PARAKEET_ROOT}/src/ggml_graph.cpp"
    "${PARAKEET_ROOT}/src/fft.cpp"
    "${PARAKEET_ROOT}/src/mel.cpp"
    "${PARAKEET_ROOT}/src/mel_gpu.cpp"
    "${PARAKEET_ROOT}/src/subsampling.cpp"
    "${PARAKEET_ROOT}/src/relpos_attention.cpp"
    "${PARAKEET_ROOT}/src/conformer.cpp"
    "${PARAKEET_ROOT}/src/pos_enc.cpp"
    "${PARAKEET_ROOT}/src/encoder.cpp"
    "${PARAKEET_ROOT}/src/streaming_encoder.cpp"
    "${PARAKEET_ROOT}/src/streaming.cpp"
    "${PARAKEET_ROOT}/src/ctc_decoder.cpp"
    "${PARAKEET_ROOT}/src/prediction.cpp"
    "${PARAKEET_ROOT}/src/joint.cpp"
    "${PARAKEET_ROOT}/src/prompt_kernel.cpp"
    "${PARAKEET_ROOT}/src/tdt.cpp"
    "${PARAKEET_ROOT}/src/rnnt.cpp"
    "${PARAKEET_ROOT}/src/transducer_batch.cpp"
    "${PARAKEET_ROOT}/src/tokenizer.cpp"
    "${PARAKEET_ROOT}/src/search.cpp"
    "${PARAKEET_ROOT}/src/transcription.cpp"
)

add_library(parakeet_static STATIC ${PARAKEET_SRC})
target_include_directories(
    parakeet_static
    PUBLIC
        "${PARAKEET_ROOT}/include"
    PRIVATE
        "${PARAKEET_ROOT}/src"
        "${PARAKEET_ROOT}/third_party"
)
target_link_libraries(parakeet_static PUBLIC ggml)

add_library(vox_parakeet_jni SHARED parakeet_jni.cpp)
target_include_directories(
    vox_parakeet_jni
    PRIVATE
        "${PARAKEET_ROOT}/include"
        "${PARAKEET_ROOT}/src"
)
target_link_libraries(
    vox_parakeet_jni
    PRIVATE
        parakeet_static
        android
        log
)
