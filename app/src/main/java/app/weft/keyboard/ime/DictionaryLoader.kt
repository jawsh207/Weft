package app.weft.keyboard.ime

import android.content.Context
import android.provider.UserDictionary
import android.util.Log
import app.weft.keyboard.predict.BinaryDictionary
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/** Android-side loading for the platform-independent prediction engine. */
object DictionaryLoader {
    private const val TAG = "DictionaryLoader"

    fun assetPath(locale: String) = "dictionaries/main_$locale.ekd"

    /** English variants fall back to US English if their dictionary is missing. */
    private const val FALLBACK = "en_US"

    /**
     * The bundled dictionary for [locale] ("en_GB"), or US English if there's
     * none for it. Returns null if no dictionary has been built at all.
     *
     * Dictionaries ship gzipped as main_<locale>.ekdz to keep the app small. (Not
     * .ekd.gz: Android's build tools quietly unpack .gz assets, which undid the
     * saving.) The first time one is used it's unpacked into the app's private,
     * non-backed-up storage, once per app version, and memory-mapped from there.
     * An uncompressed main_<locale>.ekd in the APK is used directly if present.
     * Call off the main thread: the first unpack takes a moment.
     */
    fun load(context: Context, locale: String): BinaryDictionary? =
        loadExact(context, locale) ?: if (locale != FALLBACK) loadExact(context, FALLBACK) else null

    private fun loadExact(context: Context, locale: String): BinaryDictionary? {
        val path = assetPath(locale)
        try {
            context.assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    return BinaryDictionary.map(ch, fd.startOffset, fd.length)
                }
            }
        } catch (e: FileNotFoundException) {
            // Not stored uncompressed: try the gzipped copy below.
        } catch (e: Exception) {
            // Compressed asset or other issue: read it into memory.
            runCatching {
                val bytes = context.assets.open(path).use { it.readBytes() }
                return BinaryDictionary(ByteBuffer.wrap(bytes))
            }.onFailure { Log.w(TAG, "Could not load $path", it) }
        }
        return runCatching { unpacked(context, locale)?.let { BinaryDictionary.map(it) } }
            .onFailure { Log.w(TAG, "Could not load ${packedPath(locale)}", it) }
            .getOrNull()
    }

    fun packedPath(locale: String) = "dictionaries/main_$locale.ekdz"

    /** Unpacks dictionaries/main_<locale>.ekdz once per app version; null if there's no such asset. */
    @Synchronized
    private fun unpacked(context: Context, locale: String): File? {
        val gz = packedPath(locale)
        val dir = File(context.noBackupFilesDir, "dictionaries")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val file = File(dir, "main_${locale}_$version.ekd")
        if (file.exists()) return file
        val input = try {
            context.assets.open(gz)
        } catch (e: FileNotFoundException) {
            return null
        }
        dir.mkdirs()
        // Drop copies from earlier versions of the app.
        dir.listFiles { f -> f.name.startsWith("main_${locale}_") }?.forEach { it.delete() }
        val tmp = File(dir, file.name + ".tmp")
        GZIPInputStream(input.buffered()).use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
        if (!tmp.renameTo(file)) return null
        return file
    }

    /**
     * Words from the system personal dictionary. Android lets the current
     * keyboard read it; anything else gets a SecurityException, handled here.
     */
    fun systemUserWords(context: Context): Map<String, Int> = runCatching {
        val out = HashMap<String, Int>()
        context.contentResolver.query(
            UserDictionary.Words.CONTENT_URI,
            arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.FREQUENCY),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val word = c.getString(0) ?: continue
                out[word] = c.getInt(1)
            }
        }
        out as Map<String, Int>
    }.getOrElse { emptyMap() }
}
