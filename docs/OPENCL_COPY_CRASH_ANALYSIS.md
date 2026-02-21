# OpenCL Copy Crash Analysis & Fix Proposal

## Issue Description
The application crashes with a `SIGABRT` during the transcription process (specifically in `run_encoder_chunked`) when using the OpenCL backend. The crash occurs within `ggml_backend_tensor_copy`.

**Stack Trace:**
```
ggml_abort
ggml_backend_tensor_copy
voxtral.cpp:run_encoder_chunked
```

## Root Cause
The `ggml-opencl` backend implementation does not provide a callback for `cpy_tensor`. It is explicitly set to `NULL`. While `ggml` usually attempts fallbacks, the specific way `ggml_backend_tensor_copy` is invoked (likely with views on the same backend that doesn't support copy) triggers an assertion or abort condition in `ggml`.

## Proposed Fix (in `voxtral.cpp`)
We must avoid using `ggml_backend_tensor_copy` for the OpenCL backend. Instead, we should use the robust (albeit slightly slower) method of copying data via a host buffer using `ggml_backend_tensor_get` and `ggml_backend_tensor_set`.

### Changes Required

**File:** `src/voxtral.cpp`

**Function:** `run_encoder_chunked`

**Current Code (approx line 2355):**
```cpp
            } else {
                // OPTIMIZED: Device-to-Device Copy using backend copy (avoids CPU roundtrip)
                struct ggml_init_params params = {
                    /*.mem_size   =*/ 2 * sizeof(struct ggml_tensor) + 1024,
                    /*.mem_buffer =*/ NULL,
                    /*.no_alloc   =*/ true,
                };
                struct ggml_context * ctx_copy = ggml_init(params);

                struct ggml_tensor * src_view = ggml_view_1d(
                    ctx_copy,
                    ctx->encoder_chunk_output,
                    copy_bytes / ggml_type_size(ctx->encoder_chunk_output->type),
                    src_offset
                );

                struct ggml_tensor * dst_view = ggml_view_1d(
                    ctx_copy,
                    ctx->encoder_output,
                    copy_bytes / ggml_type_size(ctx->encoder_output->type),
                    dst_offset
                );

                ggml_backend_tensor_copy(src_view, dst_view);
                ggml_free(ctx_copy);
            }
```

**Proposed Replacement:**
```cpp
            } else {
                // Manual copy via host buffer (fallback for OpenCL without cpy_tensor)
                static thread_local std::vector<uint8_t> tmp;
                if (tmp.size() < copy_bytes) {
                    tmp.resize(copy_bytes);
                }
                ggml_backend_tensor_get(ctx->encoder_chunk_output, tmp.data(), src_offset, copy_bytes);
                ggml_backend_tensor_set(ctx->encoder_output, tmp.data(), dst_offset, copy_bytes);
            }
```

## Impact
This change ensures stability on OpenCL devices by avoiding the unimplemented copy operation. The performance impact of the roundtrip to the CPU is negligible for these small chunk updates compared to the stability gain.
