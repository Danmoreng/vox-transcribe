package com.example.voxtranscribe.data.gemma

data class GemmaRuntimeStatus(
    val gpuDelegateAvailable: Boolean? = null,
    val activeBackend: String? = null,
    val fallbackReason: String? = null,
)
