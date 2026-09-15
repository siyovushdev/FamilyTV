package ru.familytv.voicelab.llm

internal object NativeQwen {
    init { System.loadLibrary("family_qwen") }
    external fun create(nativeLibraryDir: String, modelPath: String): Long
    external fun preparePrefix(handle: Long, prefix: ByteArray, cache: Boolean)
    external fun configureCpu(handle: Long, strategy: Int)
    external fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, ticket: Long): ByteArray
    external fun ticket(handle: Long): Long
    external fun cancel(handle: Long)
    external fun metrics(handle: Long): LongArray
    external fun destroy(handle: Long)
}
