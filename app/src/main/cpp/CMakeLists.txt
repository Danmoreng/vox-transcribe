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
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)

if(CMAKE_HOST_WIN32 AND CMAKE_ANDROID_NDK)
    set(
        Vulkan_GLSLC_EXECUTABLE
        "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe"
        CACHE FILEPATH "" FORCE
    )
endif()

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

set(GGML_SOURCE_ROOT "${PARAKEET_ROOT}/third_party/ggml")
set(GGML_CONFIGURE_ROOT "${CMAKE_CURRENT_BINARY_DIR}/ggml-source")
file(COPY "${GGML_SOURCE_ROOT}/" DESTINATION "${GGML_CONFIGURE_ROOT}")

if(CMAKE_HOST_WIN32)
    # ggml expands multiline glslc errors as CMake arguments, which makes its
    # extension probe enable unsupported cooperative-matrix shaders on Windows.
    set(GGML_VULKAN_CMAKE "${GGML_CONFIGURE_ROOT}/src/ggml-vulkan/CMakeLists.txt")
    file(READ "${GGML_VULKAN_CMAKE}" GGML_VULKAN_CMAKE_CONTENT)
    string(
        REPLACE
        "if (\${glslc_error} MATCHES"
        "if (\"\${glslc_error}\" MATCHES"
        GGML_VULKAN_CMAKE_CONTENT
        "${GGML_VULKAN_CMAKE_CONTENT}"
    )
    file(WRITE "${GGML_VULKAN_CMAKE}" "${GGML_VULKAN_CMAKE_CONTENT}")

endif()

# Qualcomm's Android driver either fails to link or numerically corrupts ggml's
# small-column mat-vec kernels. Decline only those ops so ggml's scheduler runs
# them on its CPU fallback while keeping the rest of the graph on Vulkan.
set(GGML_VULKAN_CPP "${GGML_CONFIGURE_ROOT}/src/ggml-vulkan/ggml-vulkan.cpp")
file(READ "${GGML_VULKAN_CPP}" GGML_VULKAN_CPP_CONTENT)
string(
    REPLACE
    "    const vk_device& device = ggml_vk_get_device(ctx->device);\n\n    const bool uses_bda ="
    "    const vk_device& device = ggml_vk_get_device(ctx->device);\n\n    if (getenv(\"VOX_VK_DISABLE_MATVEC\") != nullptr &&\n        op->op == GGML_OP_MUL_MAT && op->src[0] != nullptr) {\n        const char * weight = op->src[0]->name;\n        if (strncmp(weight, \"decoder.prediction.\", 19) == 0 ||\n            strncmp(weight, \"joint.pred.\", 11) == 0 ||\n            strncmp(weight, \"joint.joint_net.2.\", 18) == 0) {\n            return false;\n        }\n    }\n\n    const bool uses_bda ="
    GGML_VULKAN_CPP_CONTENT
    "${GGML_VULKAN_CPP_CONTENT}"
)
string(
    REPLACE
    "    } else if (src0->type == GGML_TYPE_F16 && ggml_is_permuted(src0)"
    "    } else if (getenv(\"VOX_VK_DISABLE_MATVEC\") == nullptr && src0->type == GGML_TYPE_F16 && ggml_is_permuted(src0)"
    GGML_VULKAN_CPP_CONTENT
    "${GGML_VULKAN_CPP_CONTENT}"
)
string(
    REPLACE
    "    } else if (src0->type == GGML_TYPE_F16 && !ggml_is_contiguous(src0)"
    "    } else if (getenv(\"VOX_VK_DISABLE_MATVEC\") == nullptr && src0->type == GGML_TYPE_F16 && !ggml_is_contiguous(src0)"
    GGML_VULKAN_CPP_CONTENT
    "${GGML_VULKAN_CPP_CONTENT}"
)
string(
    REPLACE
    "    } else if ((dst->ne[1] == 1 || (dst->ne[1] <= mul_mat_vec_max_cols"
    "    } else if (getenv(\"VOX_VK_DISABLE_MATVEC\") == nullptr && (dst->ne[1] == 1 || (dst->ne[1] <= mul_mat_vec_max_cols"
    GGML_VULKAN_CPP_CONTENT
    "${GGML_VULKAN_CPP_CONTENT}"
)
file(WRITE "${GGML_VULKAN_CPP}" "${GGML_VULKAN_CPP_CONTENT}")

add_subdirectory("${GGML_CONFIGURE_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/ggml")

set(PARAKEET_CONFIGURE_ROOT "${CMAKE_CURRENT_BINARY_DIR}/parakeet-source")
file(COPY "${PARAKEET_ROOT}/src/" DESTINATION "${PARAKEET_CONFIGURE_ROOT}")

# Run the numerically sensitive decoder graphs directly on a persistent CPU
# backend. This avoids invoking the cross-backend scheduler for every token.
set(PARAKEET_BACKEND_HPP "${PARAKEET_CONFIGURE_ROOT}/backend.hpp")
file(READ "${PARAKEET_BACKEND_HPP}" PARAKEET_BACKEND_HPP_CONTENT)
string(
    REPLACE
    "explicit Backend(int n_threads);"
    "explicit Backend(int n_threads, bool force_cpu_backend = false);"
    PARAKEET_BACKEND_HPP_CONTENT
    "${PARAKEET_BACKEND_HPP_CONTENT}"
)
file(WRITE "${PARAKEET_BACKEND_HPP}" "${PARAKEET_BACKEND_HPP_CONTENT}")

# Keep decoder weights resident in host memory. Adreno's small mat-vec kernels
# are not numerically reliable, and letting ggml's scheduler copy these weights
# back from Vulkan for every emitted token dominates the hybrid runtime.
set(PARAKEET_BACKEND_CPP "${PARAKEET_CONFIGURE_ROOT}/backend.cpp")
file(READ "${PARAKEET_BACKEND_CPP}" PARAKEET_BACKEND_CPP_CONTENT)
string(
    REPLACE
    "#include <string>\n#include <vector>"
    "#include <memory>\n#include <string>\n#include <unordered_map>\n#include <vector>"
    PARAKEET_BACKEND_CPP_CONTENT
    "${PARAKEET_BACKEND_CPP_CONTENT}"
)
string(
    REPLACE
    "Backend::Backend(int n_threads) : impl_(new Impl()) {"
    "Backend::Backend(int n_threads, bool force_cpu_backend) : impl_(new Impl()) {"
    PARAKEET_BACKEND_CPP_CONTENT
    "${PARAKEET_BACKEND_CPP_CONTENT}"
)
string(
    REPLACE
    "    if (!force_cpu) {"
    "    if (!force_cpu_backend && !force_cpu) {"
    PARAKEET_BACKEND_CPP_CONTENT
    "${PARAKEET_BACKEND_CPP_CONTENT}"
)
string(
    REPLACE
    "ggml_tensor* clone_weight(ggml_context* /*ctx*/, const ModelLoader& ml,\n                          const char* name) {"
    "struct CpuWeightMirror {\n    ggml_context* ctx = nullptr;\n    ggml_backend_buffer_t buffer = nullptr;\n    ggml_tensor* tensor = nullptr;\n\n    ~CpuWeightMirror() {\n        if (buffer) ggml_backend_buffer_free(buffer);\n        if (ctx) ggml_free(ctx);\n    }\n};\n\nstatic ggml_tensor* cpu_weight_mirror(ggml_tensor* src) {\n    static std::unordered_map<ggml_tensor*, std::unique_ptr<CpuWeightMirror>> mirrors;\n    auto found = mirrors.find(src);\n    if (found != mirrors.end()) return found->second->tensor;\n\n    auto mirror = std::make_unique<CpuWeightMirror>();\n    ggml_init_params params = {\n        ggml_tensor_overhead() * 2,\n        nullptr,\n        true,\n    };\n    mirror->ctx = ggml_init(params);\n    GGML_ASSERT(mirror->ctx != nullptr);\n    mirror->tensor = ggml_new_tensor(mirror->ctx, src->type, GGML_MAX_DIMS, src->ne);\n    ggml_set_name(mirror->tensor, src->name);\n    mirror->buffer = ggml_backend_alloc_ctx_tensors_from_buft(\n        mirror->ctx, ggml_backend_cpu_buffer_type());\n    GGML_ASSERT(mirror->buffer != nullptr);\n    ggml_backend_tensor_copy(src, mirror->tensor);\n\n    ggml_tensor* result = mirror->tensor;\n    mirrors.emplace(src, std::move(mirror));\n    return result;\n}\n\nggml_tensor* clone_weight(ggml_context* /*ctx*/, const ModelLoader& ml,\n                          const char* name) {"
    PARAKEET_BACKEND_CPP_CONTENT
    "${PARAKEET_BACKEND_CPP_CONTENT}"
)
string(
    REPLACE
    "    ggml_tensor* src = ml.tensor(name);\n    assert(src && \"missing tensor\");\n    return src;"
    "    ggml_tensor* src = ml.tensor(name);\n    assert(src && \"missing tensor\");\n    if (getenv(\"VOX_VK_DISABLE_MATVEC\") != nullptr &&\n        std::string(global_backend().device_name()) != \"cpu\" &&\n        (strncmp(name, \"decoder.prediction.\", 19) == 0 ||\n         strncmp(name, \"joint.pred.\", 11) == 0 ||\n         strncmp(name, \"joint.joint_net.2.\", 18) == 0)) {\n        return cpu_weight_mirror(src);\n    }\n    return src;"
    PARAKEET_BACKEND_CPP_CONTENT
    "${PARAKEET_BACKEND_CPP_CONTENT}"
)
file(WRITE "${PARAKEET_BACKEND_CPP}" "${PARAKEET_BACKEND_CPP_CONTENT}")

set(PARAKEET_GRAPH_HPP "${PARAKEET_CONFIGURE_ROOT}/ggml_graph.hpp")
file(READ "${PARAKEET_GRAPH_HPP}" PARAKEET_GRAPH_HPP_CONTENT)
string(
    REPLACE
    "bool run_graph(size_t mem_bytes, int n_threads,\n               const std::function<ggml_tensor*(ggml_context*)>& build,\n               std::vector<float>& out);"
    "bool run_graph(size_t mem_bytes, int n_threads,\n               const std::function<ggml_tensor*(ggml_context*)>& build,\n               std::vector<float>& out);\n\nbool run_graph_cpu(size_t mem_bytes, int n_threads,\n                   const std::function<ggml_tensor*(ggml_context*)>& build,\n                   std::vector<float>& out);"
    PARAKEET_GRAPH_HPP_CONTENT
    "${PARAKEET_GRAPH_HPP_CONTENT}"
)
file(WRITE "${PARAKEET_GRAPH_HPP}" "${PARAKEET_GRAPH_HPP_CONTENT}")

set(PARAKEET_GRAPH_CPP "${PARAKEET_CONFIGURE_ROOT}/ggml_graph.cpp")
file(READ "${PARAKEET_GRAPH_CPP}" PARAKEET_GRAPH_CPP_CONTENT)
string(
    REPLACE
    "std::unique_ptr<Backend> g_backend;\nint g_backend_threads = 0;"
    "std::unique_ptr<Backend> g_backend;\nstd::unique_ptr<Backend> g_cpu_backend;\nint g_backend_threads = 0;\nint g_cpu_backend_threads = 0;"
    PARAKEET_GRAPH_CPP_CONTENT
    "${PARAKEET_GRAPH_CPP_CONTENT}"
)
string(
    REPLACE
    "    g_backend.reset();\n    g_backend_threads = 0;"
    "    g_cpu_backend.reset();\n    g_backend.reset();\n    g_cpu_backend_threads = 0;\n    g_backend_threads = 0;"
    PARAKEET_GRAPH_CPP_CONTENT
    "${PARAKEET_GRAPH_CPP_CONTENT}"
)
string(
    REPLACE
    "\n} // namespace pk"
    "\nbool run_graph_cpu(size_t /*mem_bytes*/, int n_threads,\n                   const std::function<ggml_tensor*(ggml_context*)>& build,\n                   std::vector<float>& out) {\n    std::lock_guard<std::mutex> lock(g_backend_mutex);\n    const int g = g_num_threads.load(std::memory_order_relaxed);\n    const int n = g > 0 ? g : (n_threads > 0 ? n_threads : kDefaultThreads);\n    if (!g_cpu_backend) {\n        g_cpu_backend = std::make_unique<Backend>(n, true);\n        g_cpu_backend_threads = n;\n    } else if (n != g_cpu_backend_threads) {\n        g_cpu_backend->set_n_threads(n);\n        g_cpu_backend_threads = n;\n    }\n    return g_cpu_backend->compute(build, out);\n}\n\n} // namespace pk"
    PARAKEET_GRAPH_CPP_CONTENT
    "${PARAKEET_GRAPH_CPP_CONTENT}"
)
file(WRITE "${PARAKEET_GRAPH_CPP}" "${PARAKEET_GRAPH_CPP_CONTENT}")

set(PARAKEET_PREDICTION_CPP "${PARAKEET_CONFIGURE_ROOT}/prediction.cpp")
file(READ "${PARAKEET_PREDICTION_CPP}" PARAKEET_PREDICTION_CPP_CONTENT)
string(
    REPLACE
    "pk::run_graph("
    "pk::run_graph_cpu("
    PARAKEET_PREDICTION_CPP_CONTENT
    "${PARAKEET_PREDICTION_CPP_CONTENT}"
)
file(WRITE "${PARAKEET_PREDICTION_CPP}" "${PARAKEET_PREDICTION_CPP_CONTENT}")

set(PARAKEET_JOINT_CPP "${PARAKEET_CONFIGURE_ROOT}/joint.cpp")
file(READ "${PARAKEET_JOINT_CPP}" PARAKEET_JOINT_CPP_CONTENT)
string(
    REPLACE
    "bool ok = pk::run_graph(0, 0,"
    "bool ok = pk::run_graph_cpu(0, 0,"
    PARAKEET_JOINT_CPP_CONTENT
    "${PARAKEET_JOINT_CPP_CONTENT}"
)
file(WRITE "${PARAKEET_JOINT_CPP}" "${PARAKEET_JOINT_CPP_CONTENT}")

set(PARAKEET_SRC
    "${PARAKEET_CONFIGURE_ROOT}/parakeet.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/model.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/parakeet_capi.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/common.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/audio_io.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/model_loader.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/backend.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/ggml_graph.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/fft.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/mel.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/mel_gpu.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/subsampling.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/relpos_attention.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/conformer.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/pos_enc.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/encoder.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/streaming_encoder.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/streaming.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/ctc_decoder.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/prediction.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/joint.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/prompt_kernel.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/tdt.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/rnnt.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/transducer_batch.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/tokenizer.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/search.cpp"
    "${PARAKEET_CONFIGURE_ROOT}/transcription.cpp"
)

add_library(parakeet_static STATIC ${PARAKEET_SRC})
target_include_directories(
    parakeet_static
    PUBLIC
        "${PARAKEET_ROOT}/include"
    PRIVATE
        "${PARAKEET_CONFIGURE_ROOT}"
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
