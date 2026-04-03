package com.example.voxtranscribe.data.gemma

enum class GemmaModelId {
    GEMMA_4_E2B,
    GEMMA_4_E4B
}

data class GemmaModelSpec(
    val id: GemmaModelId,
    val displayName: String,
    val expectedFileName: String,
    val minDeviceMemoryGb: Int,
    val supportsAudio: Boolean = true,
    val supportsText: Boolean = true,
)

object GemmaModelCatalog {
    const val supportedImportExtension = ".litertlm"

    val supportedModels: List<GemmaModelSpec> = listOf(
        GemmaModelSpec(
            id = GemmaModelId.GEMMA_4_E2B,
            displayName = "Gemma 4 E2B",
            expectedFileName = "gemma-4-E2B-it.litertlm",
            minDeviceMemoryGb = 8,
        ),
        GemmaModelSpec(
            id = GemmaModelId.GEMMA_4_E4B,
            displayName = "Gemma 4 E4B",
            expectedFileName = "gemma-4-E4B-it.litertlm",
            minDeviceMemoryGb = 12,
        ),
    )

    fun findSupportedModelForImport(fileName: String): GemmaModelSpec? {
        val normalizedFileName = fileName.trim().lowercase()
        return supportedModels.firstOrNull { it.expectedFileName.lowercase() == normalizedFileName }
    }

    fun isSupportedImportFile(fileName: String): Boolean {
        return findSupportedModelForImport(fileName) != null
    }
}
