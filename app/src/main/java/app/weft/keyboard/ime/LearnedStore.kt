package app.weft.keyboard.ime

import android.content.Context
import app.weft.keyboard.predict.LearnedWords
import java.io.File

/**
 * The one copy of what Weft has learned, shared by the keyboard and the
 * settings screen in the same app process. Editing a word in settings changes
 * what the keyboard suggests at once, and neither side can overwrite the
 * other's changes when it saves.
 */
object LearnedStore {
    @Volatile
    private var instance: LearnedWords? = null

    /** The shared store; call [LearnedWords.ensureLoaded] off the main thread before reading it. */
    fun get(context: Context): LearnedWords = instance ?: synchronized(this) {
        instance ?: LearnedWords(
            File(context.filesDir, LearnedWords.WORDS_FILE),
            File(context.filesDir, LearnedWords.HISTORY_FILE),
        ).also { instance = it }
    }
}
