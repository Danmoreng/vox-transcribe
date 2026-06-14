package com.example.voxtranscribe.data.parakeet

enum class ParakeetModelId {
    NEMOTRON_3_5_ASR_STREAMING_0_6B,
}

data class ParakeetModelSpec(
    val id: ParakeetModelId,
    val displayName: String,
    val storageFileName: String,
    val downloadSizeBytes: Long,
    val minDeviceMemoryGb: Int,
)

object ParakeetModelCatalog {
    private const val streamingModelRepo = "onnx-community/nemotron-3.5-asr-streaming-0.6b-onnx-int4"

    val streamingModel = ParakeetModelSpec(
        id = ParakeetModelId.NEMOTRON_3_5_ASR_STREAMING_0_6B,
        displayName = "Nemotron 3.5 ASR Streaming 0.6B ONNX",
        storageFileName = "nemotron-onnx",
        downloadSizeBytes = 1_500_000_000L,
        minDeviceMemoryGb = 8,
    )

    val streamingModelFiles: List<String> = listOf(
        "audio_processor_config.json",
        "decoder.onnx",
        "decoder.onnx.data",
        "encoder.onnx",
        "encoder.onnx.data",
        "genai_config.json",
        "joint.onnx",
        "joint.onnx.data",
        "model_config.json",
        "silero_vad.onnx",
        "tokenizer_config.json",
        "tokenizer.json",
        "vocab.txt",
    )

    val supportedModels: List<ParakeetModelSpec> = listOf(streamingModel)

    fun streamingModelDownloadUrl(fileName: String): String {
        return "https://huggingface.co/$streamingModelRepo/resolve/main/$fileName?download=true"
    }
}
