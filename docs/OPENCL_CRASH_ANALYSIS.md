# OpenCL Crash Analysis & Fix Proposal

## Issue Description
The application crashes with a `SIGABRT` shortly after initializing the `voxtral` context when using the OpenCL backend. The crash occurs within `ggml_backend_tensor_memset`, which is called during stream initialization to clear the KV cache.

**Stack Trace:**
```
ggml_abort
ggml_backend_tensor_memset
voxtral_stream_create (via stream_reset_persistent_decode_state -> clear_kv_cache)
Java_com_example_voxtranscribe_data_VoxtralJni_streamInit
```

## Root Cause
The `ggml-opencl` backend implementation (in `ggml/src/ggml-opencl/ggml-opencl.cpp`) does not provide a callback for `memset_tensor`. It is explicitly set to `NULL`:

```cpp
/* .memset_tensor   = */ NULL,
```

The core `ggml` library's `ggml_backend_tensor_memset` function likely asserts or aborts when it encounters a backend without this capability, causing the crash.

## Proposed Fix (in `voxtral.cpp`)
Since we cannot easily modify `ggml-opencl` to add a kernel for memset, we must modify `voxtral.cpp` to avoid using `ggml_backend_tensor_memset`. Instead, we can use `ggml_backend_tensor_set` to copy a zero-filled buffer from the host to the device. This is a robust, backend-agnostic workaround.

### Changes Required

**File:** `src/voxtral.cpp`

#### 1. Function: `clear_kv_cache`

**Current Code:**
```cpp
static void clear_kv_cache(voxtral_context * ctx) {
    if (!ctx || !ctx->kv_self_k || !ctx->kv_self_v) {
        return;
    }
    ggml_backend_tensor_memset(ctx->kv_self_k, 0, 0, ggml_nbytes(ctx->kv_self_k));
    ggml_backend_tensor_memset(ctx->kv_self_v, 0, 0, ggml_nbytes(ctx->kv_self_v));
    ctx->kv_used = 0;
}
```

**Proposed Replacement:**
```cpp
static void clear_kv_cache(voxtral_context * ctx) {
    if (!ctx || !ctx->kv_self_k || !ctx->kv_self_v) {
        return;
    }
    
    // Workaround for OpenCL backend missing memset support: use host-to-device copy
    size_t k_size = ggml_nbytes(ctx->kv_self_k);
    std::vector<uint8_t> zeros(k_size, 0);
    ggml_backend_tensor_set(ctx->kv_self_k, zeros.data(), 0, k_size);
    
    size_t v_size = ggml_nbytes(ctx->kv_self_v);
    if (v_size != k_size) zeros.resize(v_size, 0);
    ggml_backend_tensor_set(ctx->kv_self_v, zeros.data(), 0, v_size);

    ctx->kv_used = 0;
}
```

#### 2. Function: `kv_cache_shift_left`

**Current Code:**
```cpp
        ggml_backend_tensor_get(ctx->kv_self_k, tmp.data(), head_off, moved_bytes);
        ggml_backend_tensor_set(ctx->kv_self_k, tmp.data(), layer_off, moved_bytes);
        ggml_backend_tensor_memset(ctx->kv_self_k, 0, tail_off, (size_t) shift * row_bytes);

        ggml_backend_tensor_get(ctx->kv_self_v, tmp.data(), head_off, moved_bytes);
        ggml_backend_tensor_set(ctx->kv_self_v, tmp.data(), layer_off, moved_bytes);
        ggml_backend_tensor_memset(ctx->kv_self_v, 0, tail_off, (size_t) shift * row_bytes);
```

**Proposed Replacement:**
```cpp
    std::vector<uint8_t> tmp((size_t) (window - shift) * row_bytes);
    std::vector<uint8_t> zeros((size_t) shift * row_bytes, 0); // Pre-allocate zeros for tail

    for (int32_t l = 0; l < VOXTRAL_DEC_LAYERS; ++l) {
        const size_t layer_off = (size_t) l * layer_stride;
        const size_t moved_bytes = (size_t) (window - shift) * row_bytes;
        const size_t head_off = layer_off + (size_t) shift * row_bytes;
        const size_t tail_off = layer_off + (size_t) (window - shift) * row_bytes;

        ggml_backend_tensor_get(ctx->kv_self_k, tmp.data(), head_off, moved_bytes);
        ggml_backend_tensor_set(ctx->kv_self_k, tmp.data(), layer_off, moved_bytes);
        // Replace memset with set
        ggml_backend_tensor_set(ctx->kv_self_k, zeros.data(), tail_off, (size_t) shift * row_bytes);

        ggml_backend_tensor_get(ctx->kv_self_v, tmp.data(), head_off, moved_bytes);
        ggml_backend_tensor_set(ctx->kv_self_v, tmp.data(), layer_off, moved_bytes);
        // Replace memset with set
        ggml_backend_tensor_set(ctx->kv_self_v, zeros.data(), tail_off, (size_t) shift * row_bytes);
    }
```

## Impact
This change makes `voxtral` compatible with `ggml-opencl` (and any other backend missing `memset`) without sacrificing significant performance, as these operations are initialization or occasional maintenance tasks.
