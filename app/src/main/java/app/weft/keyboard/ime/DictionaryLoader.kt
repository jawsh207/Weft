package app.weft.keyboard.ime

import android.content.Context
import android.provider.UserDictionary
import android.util.Log
import app.weft.keyboard.predict.BinaryDictionary
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** Android-side loading for the platform-independent prediction engine. */
object DictionaryLoader {
    private const val TAG = "DictionaryLoader"

    fun assetPath(locale: String) = "dictionaries/main_$locale.ekd"

    /**
     * Memory-maps the bundled dictionary for [locale] ("en_US"). Assets with the
     * .ekd extension are stored uncompressed (see app/build.gradle.kts), so this
     * is zero-copy. Returns null if no dictionary has been built yet.
     */
    fun load(context: Context, locale: String): BinaryDictionary? {
        val path = assetPath(locale)
        return try {
            context.assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    BinaryDictionary.map(ch, fd.startOffset, fd.length)
                }
            }
        } catch (e: FileNotFoundException) {
            Log.i(TAG, "No dictionary at $path; run tools/dictgen/make_dict.py $locale")
            null
        } catch (e: Exception) {
            // Compressed asset or other issue: fall back to reading it into memory.
            runCatching {
                val bytes = context.assets.open(path).use { it.readBytes() }
                BinaryDictionary(ByteBuffer.wrap(bytes))
            }.onFailure { Log.w(TAG, "Could not load $path", it) }.getOrNull()
        }
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
