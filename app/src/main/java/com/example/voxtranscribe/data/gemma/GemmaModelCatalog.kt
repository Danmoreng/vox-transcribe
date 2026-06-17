package com.example.voxtranscribe.data.gemma

enum class GemmaModelId {
    GEMMA_4_E2B,
    GEMMA_4_E4B
}

data class GemmaModelSpec(
    val id: GemmaModelId,
    val displayName: String,
    val expectedFileName: String,
    val sourceRepo: String,
    val downloadSizeBytes: Long,
    val minDeviceMemoryGb: Int,
)

object GemmaModelCatalog {
    val supportedModels: List<GemmaModelSpec> = listOf(
        GemmaModelSpec(
            id = GemmaModelId.GEMMA_4_E4B,
            displayName = "Gemma 4 E4B",
            expectedFileName = "gemma-4-E4B-it.litertlm",
            sourceRepo = "litert-community/gemma-4-E4B-it-litert-lm",
            downloadSizeBytes = 3_930_000_000L,
            minDeviceMemoryGb = 12,
        ),
    )

    val recommendedTextModel: GemmaModelSpec = supportedModels.first { it.id == GemmaModelId.GEMMA_4_E4B }

    fun downloadUrl(spec: GemmaModelSpec): String {
        return "https://huggingface.co/${spec.sourceRepo}/resolve/main/${spec.expectedFileName}?download=true"
    }
}
