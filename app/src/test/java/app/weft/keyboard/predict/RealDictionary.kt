package app.weft.keyboard.predict

import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The real US English dictionary from the app's assets, for benchmarks. It ships
 * gzipped (main_en_US.ekdz), so it's unpacked once per test run. Null if it
 * hasn't been built.
 */
object RealDictionary {
    private val candidates = listOf("src/main/assets/dictionaries", "app/src/main/assets/dictionaries")

    val file: File? by lazy {
        val dir = candidates.map(::File).firstOrNull { it.isDirectory } ?: return@lazy null
        File(dir, "main_en_US.ekd").takeIf { it.exists() }?.let { return@lazy it }
        val packed = File(dir, "main_en_US.ekdz").takeIf { it.exists() } ?: return@lazy null
        File.createTempFile("main_en_US", ".ekd").apply {
            deleteOnExit()
            GZIPInputStream(packed.inputStream().buffered()).use { inp -> outputStream().use { inp.copyTo(it) } }
        }
    }

    fun load(): BinaryDictionary? = file?.let { BinaryDictionary.map(it) }
}

/**
 * The real next-word model from the app's assets (models/next_en.wlmz, built by tools/nextword),
 * unpacked once per test run. Null if it hasn't been built.
 */
object RealNextWordModel {
    private val candidates = listOf("src/main/assets/models", "app/src/main/assets/models")

    val file: File? by lazy {
        val dir = candidates.map(::File).firstOrNull { it.isDirectory } ?: return@lazy null
        val packed = File(dir, "next_en.wlmz").takeIf { it.exists() } ?: return@lazy null
        File.createTempFile("next_en", ".wlm").apply {
            deleteOnExit()
            GZIPInputStream(packed.inputStream().buffered()).use { inp -> outputStream().use { inp.copyTo(it) } }
        }
    }

    fun load(): NextWordModel? = file?.let { NextWordModel.map(it) }
}
