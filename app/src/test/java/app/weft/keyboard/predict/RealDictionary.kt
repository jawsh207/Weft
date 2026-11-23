package app.weft.keyboard.predict

import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The real English dictionaries from the app's assets, for benchmarks. English ships as one
 * shared dictionary plus a patch per country (DictionaryPatch); this rebuilds one the way the app
 * does, once per test run. Null if they haven't been built.
 */
object RealDictionary {
    private val candidates = listOf("src/main/assets/dictionaries", "app/src/main/assets/dictionaries")
    private val built = HashMap<String, File?>()

    /** The US English dictionary, as the benchmarks use. */
    val file: File? get() = file("en_US")

    /** The dictionary for [locale] ("en_GB") as an unpacked file. */
    @Synchronized
    fun file(locale: String): File? = built.getOrPut(locale) {
        val dir = candidates.map(::File).firstOrNull { it.isDirectory } ?: return@getOrPut null
        File(dir, "main_$locale.ekd").takeIf { it.exists() }?.let { return@getOrPut it }
        val bytes = File(dir, "main_$locale.ekdz").takeIf { it.exists() }?.let(::gunzip)
            ?: run {
                val patch = File(dir, "patch_$locale.ekdp").takeIf { it.exists() } ?: return@getOrPut null
                val base = File(dir, "main_${locale.substringBefore('_')}.ekdz").takeIf { it.exists() }
                    ?: return@getOrPut null
                DictionaryPatch.apply(gunzip(base), gunzip(patch))
            }
        File.createTempFile("main_$locale", ".ekd").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
    }

    private fun gunzip(f: File) = GZIPInputStream(f.inputStream().buffered()).use { it.readBytes() }

    fun load(): BinaryDictionary? = file?.let { BinaryDictionary.map(it) }

    fun load(locale: String): BinaryDictionary? = file(locale)?.let { BinaryDictionary.map(it) }
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
