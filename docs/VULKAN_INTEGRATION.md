# Vulkan Integration Guide for Windows Host

This document details the steps taken to enable the Vulkan backend for `voxtral.cpp` on Android when building from a Windows host.

## 1. Problem Statement

Enabling `GGML_VULKAN=ON` caused two primary classes of build failures:

1.  **Host Toolchain Issues (Windows):** The `vulkan-shaders-gen` tool, which runs on the host to compile shaders, failed to build because:
    *   It incorrectly detected the Android NDK Clang compiler as the host compiler.
    *   It failed to find the MSVC compiler (`cl.exe`) automatically.
    *   CMake path handling on Windows caused syntax errors (backslashes in paths) in generated toolchain files.
    *   Missing `CMAKE_MT` definition caused linker errors (`LNK1181`).
    *   Missing include paths caused `C1034: iostream not found`.

2.  **Android Target Issues (NDK):**
    *   The NDK (API 26) `libvulkan.so` does not export Vulkan 1.1 symbols like `vkGetPhysicalDeviceFeatures2`, causing linker errors.
    *   The NDK does not provide the C++ `vulkan.hpp` headers, only the C `vulkan_core.h`.
    *   Header version mismatches between downloaded C++ headers and NDK C headers triggered static assertions.

## 2. Solutions Applied

### A. Windows Host Toolchain Configuration (`app/build.gradle.kts`)

We explicitly configured the CMake arguments to point to the Visual Studio 2022 Build Tools. **Note: These paths are specific to the current machine and may need adjustment for other environments.**

*   **Compilers:** Explicitly set `HOST_C_COMPILER` and `HOST_CXX_COMPILER` to `cl.exe`.
*   **Manifest Tool:** Explicitly set `CMAKE_MT` to `mt.exe` from the Windows SDK.
*   **Library Paths:** Added `CMAKE_SYSTEM_LIBRARY_PATH` pointing to the Windows SDK `Lib` directories (`um`, `ucrt`) and MSVC `lib`.

### B. CMake Fixes for Windows Paths (`external/voxtral/...`)

We patched the `ggml` submodule files to handle Windows paths correctly:

*   **`host-toolchain.cmake.in`:**
    *   Used bracketed strings `[=[ ... ]=]` for all paths to prevent CMake from interpreting backslashes as escape sequences.
    *   Added `string(REPLACE "" "/" ...)` normalization for `CMAKE_RUNTIME_OUTPUT_DIRECTORY`.
    *   Added `/LIBPATH` linker flags to ensure `kernel32.lib` is found.
    *   Added `/I` include flags to `CMAKE_CXX_FLAGS` to find standard headers (`iostream`, `windows.h`).

*   **`ggml-vulkan/CMakeLists.txt`:**
    *   Updated `detect_host_compiler` to respect pre-defined `HOST_...` variables.
    *   Forced path normalization using `file(TO_CMAKE_PATH ...)` for compiler paths.

### C. Android Vulkan Headers & Linking (`app/src/main/cpp/CMakeLists.txt`)

*   **Downloaded Headers:** Cloned the `Vulkan-Hpp` repository (tag `v1.3.275`) to `external/vulkan-headers` to match the NDK's Vulkan version.
*   **Include Directories:** Added `include_directories(../../../../external/vulkan-headers)` *before* adding the `voxtral` subdirectory to ensure propagation.
*   **Dynamic Dispatch:** Added `add_compile_definitions(VK_NO_PROTOTYPES)`. This prevents `vulkan.h` from defining static prototypes, forcing the application to use the dynamic dispatcher for all Vulkan calls. This resolves the missing symbol errors for Vulkan 1.1 functions on older Android API levels.

## 3. Configuration Summary

**`app/build.gradle.kts`:**
```kotlin
arguments(
    "-DGGML_OPENCL=OFF",
    "-DVOXTRAL_AUTO_DETECT_OPENCL=OFF",
    "-DGGML_VULKAN=ON",
    "-DVOXTRAL_AUTO_DETECT_VULKAN=ON",
    // ... Host compiler paths ...
)
```

**`app/src/main/cpp/CMakeLists.txt`:**
```cmake
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
add_compile_definitions(VK_NO_PROTOTYPES)
include_directories(../../../../external/vulkan-headers)
```

## 4. How to Verify

1.  **Clean:** Delete `app/.cxx` and `app/build`.
2.  **Build:** Run `AssembleDebug`.
3.  **Run:** Launch app. Check logs for `ggml_vulkan: ...` initialization messages. The encoder step should be significantly faster (<1s for 3s audio).
