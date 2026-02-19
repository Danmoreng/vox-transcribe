cmake_minimum_required(VERSION 3.22.1)

project("voxtranscribe")

# Set flags for ggml to avoid build errors and optimize for Android
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(VOXTRAL_AUTO_DETECT_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

# Disable native optimizations that might break on ARM (e.g. -march=native)
set(VOXTRAL_NATIVE_OPT OFF CACHE BOOL "" FORCE)
set(VOXTRAL_AUTO_DETECT_CPU ON CACHE BOOL "" FORCE) # Let runtime detect features

# Disable warnings as errors for external code
set(VOXTRAL_WARNINGS_AS_ERRORS OFF CACHE BOOL "" FORCE)

# Force standard O3 optimization, avoid Ofast/ffast-math if they are causing issues
add_compile_options(-O3 -fno-fast-math)

# Add ggml submodule
add_subdirectory(../../../../external/voxtral/ggml ggml_build)

# Add voxtral implementation as a static library
add_library(voxtral_lib STATIC
    ../../../../external/voxtral/src/voxtral.cpp
)

target_include_directories(voxtral_lib PUBLIC
    ../../../../external/voxtral/include
)

# Link voxtral against ggml
target_link_libraries(voxtral_lib PUBLIC ggml)

# Add our JNI wrapper as a shared library
add_library(voxtral_jni SHARED
    voxtral_jni.cpp
)

# Include directories for JNI wrapper
target_include_directories(voxtral_jni PRIVATE
    ../../../../external/voxtral/include
)

# Link JNI wrapper against voxtral_lib and Android logging
target_link_libraries(voxtral_jni PRIVATE
    voxtral_lib
    android
    log
)
