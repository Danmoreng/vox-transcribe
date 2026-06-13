package com.example.voxtranscribe.data.parakeet

enum class ParakeetModelId {
    NEMOTRON_3_5_ASR_STREAMING_0_6B,
}

data class ParakeetModelSpec(
    val id: ParakeetModelId,
    val displayName: String,
    val storageFileName: String,
    val sourceModel: String,
    val minDeviceMemoryGb: Int,
)

object ParakeetModelCatalog {
    const val supportedImportExtension = ".zip"

    val streamingModel = ParakeetModelSpec(
        id = ParakeetModelId.NEMOTRON_3_5_ASR_STREAMING_0_6B,
        displayName = "Nemotron 3.5 ASR Streaming 0.6B ONNX",
        storageFileName = "nemotron-onnx",
        sourceModel = "onnx-community/nemotron-3.5-asr-streaming-0.6b-onnx-int4",
        minDeviceMemoryGb = 8,
    )

    val supportedModels: List<ParakeetModelSpec> = listOf(streamingModel)

    fun isSupportedImportFile(fileName: String): Boolean {
        return fileName.trim().lowercase().endsWith(supportedImportExtension)
    }
}
