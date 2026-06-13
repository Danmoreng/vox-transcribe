package com.example.voxtranscribe.data.nemotron

internal object NemotronNative {
    init {
        System.loadLibrary("vox_nemotron_jni")
    }

    external fun transcribe(
        modelPath: String,
        samples: FloatArray,
        languageId: Int,
    ): String

    external fun loadModel(modelPath: String): Long
    external fun freeModel(handle: Long)
    external fun beginStream(modelHandle: Long, languageId: Int): Long
    external fun feedStream(streamHandle: Long, samples: FloatArray): String
    external fun finalizeStream(streamHandle: Long): String
    external fun freeStream(streamHandle: Long)
}
