package app.weft.keyboard.ime

import android.content.Context
import android.provider.UserDictionary
import android.util.Log
import app.weft.keyboard.predict.BinaryDictionary
import app.weft.keyboard.predict.DictionaryPatch
import app.weft.keyboard.predict.NextWordModel
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/**
 * Finds the word list for a keyboard language inside the app and opens it, opens
 * the next-word model, and reads the phone's personal dictionary. The prediction
 * code itself (the predict package) knows nothing about Android; this is the part
 * that does.
 */
object DictionaryLoader {
    private const val TAG = "DictionaryLoader"

    /**
     * Where an uncompressed dictionary for [locale] would sit in the APK (builds normally ship
     * [packedPath]).
     */
    fun assetPath(locale: String) = "dictionaries/main_$locale.ekd"

    /** English variants fall back to US English if their dictionary is missing. */
    private const val FALLBACK = "en_US"

    /**
     * The bundled dictionary for [locale] ("en_GB"), or US English if there's
     * none for it. Returns null if no dictionary has been built at all.
     *
     * Dictionaries ship gzipped to keep the app small. (Not .ekd.gz: Android's
     * build tools quietly unpack .gz assets, which undid the saving.) English
     * ships once, as main_en.ekdz plus a small patch per country
     * (patch_<locale>.ekdp); another language would ship as main_<locale>.ekdz.
     * The first time one is used it's unpacked (and patched) into the app's
     * private, non-backed-up storage, once per app version, and memory-mapped
     * from there. An uncompressed main_<locale>.ekd in the APK is used directly
     * if present.
     * Call off the main thread: the first unpack takes a moment.
     */
    fun load(context: Context, locale: String): BinaryDictionary? =
        loadExact(context, locale) ?: if (locale != FALLBACK) loadExact(context, FALLBACK) else null

    /**
     * The dictionary for exactly [locale], with no fallback: uncompressed asset first, then the
     * gzipped one.
     */
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
        return runCatching { (unpacked(context, locale) ?: patched(context, locale))?.let { BinaryDictionary.map(it) } }
            .onFailure { Log.w(TAG, "Could not load the $locale dictionary", it) }
            .getOrNull()
    }

    /** Where the gzipped dictionary for [locale] sits in the APK. */
    fun packedPath(locale: String) = "dictionaries/main_$locale.ekdz"

    /** Unpacks dictionaries/main_<locale>.ekdz once per app version; null if there's no such asset. */
    private fun unpacked(context: Context, locale: String): File? =
        unpack(context, packedPath(locale), "dictionaries", "main_${locale}_", ".ekd")

    /**
     * Rebuilds the dictionary for [locale] from its language's shared one (main_en.ekdz) and the
     * locale's patch (patch_en_GB.ekdp; see [DictionaryPatch]), once per app version, and saves it
     * where [unpacked] would. Null if there's no patch for [locale].
     */
    @Synchronized
    private fun patched(context: Context, locale: String): File? {
        val dir = File(context.noBackupFilesDir, "dictionaries")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val prefix = "main_${locale}_"
        val file = File(dir, "$prefix$version.ekd")
        if (file.exists()) return file
        val patch = try {
            gunzip(context, "dictionaries/patch_$locale.ekdp")
        } catch (e: FileNotFoundException) {
            return null
        }
        val base = gunzip(context, "dictionaries/main_${locale.substringBefore('_')}.ekdz")
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeBytes(DictionaryPatch.apply(base, patch))
        if (!tmp.renameTo(file)) return null
        return file
    }

    private fun gunzip(context: Context, asset: String): ByteArray =
        GZIPInputStream(context.assets.open(asset).buffered()).use { it.readBytes() }

    /** Where the gzipped English next-word model sits in the APK (tools/nextword builds it). */
    const val MODEL_PATH = "models/next_en.wlmz"

    /**
     * The next-word model for English (one model serves every English variant), or null if the
     * build has none. Unpacked once per app version like the dictionaries, then memory-mapped.
     * Call off the main thread.
     */
    fun loadNextWordModel(context: Context): NextWordModel? =
        runCatching { unpack(context, MODEL_PATH, "models", "next_en_", ".wlm")?.let { NextWordModel.map(it) } }
            .onFailure { Log.w(TAG, "Could not load $MODEL_PATH", it) }
            .getOrNull()

    /**
     * Unpacks the gzipped asset [gz] into [dirName] in the app's private, non-backed-up storage as
     * <[prefix]><app version><[suffix]>, once per app version; null if there's no such asset.
     */
    @Synchronized
    private fun unpack(context: Context, gz: String, dirName: String, prefix: String, suffix: String): File? {
        val dir = File(context.noBackupFilesDir, dirName)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val file = File(dir, "$prefix$version$suffix")
        if (file.exists()) return file
        val input = try {
            context.assets.open(gz)
        } catch (e: FileNotFoundException) {
            return null
        }
        dir.mkdirs()
        // Drop copies from earlier versions of the app.
        dir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }
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
