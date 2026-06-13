package com.example.voxtranscribe.data.parakeet

internal object ParakeetNative {
    init {
        System.loadLibrary("vox_parakeet_jni")
    }

    external fun abiVersion(): Int
    external fun configureBackend(deviceName: String)
    external fun setThreadCount(threadCount: Int)
    external fun activeBackendName(): String
    external fun loadModel(modelPath: String): Long
    external fun freeModel(handle: Long)
    external fun lastError(handle: Long): String
    external fun beginStream(modelHandle: Long, targetLang: String): Long
    external fun feedStream(streamHandle: Long, samples: FloatArray): String?
    external fun lastFeedHadEou(streamHandle: Long): Boolean
    external fun finalizeStream(streamHandle: Long): String?
    external fun freeStream(streamHandle: Long)
}
