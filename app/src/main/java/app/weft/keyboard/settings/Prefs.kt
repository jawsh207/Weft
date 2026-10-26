package app.weft.keyboard.settings

import android.content.Context
import android.content.SharedPreferences

/** Settings shared between the setup screen and the keyboard service. */
object Prefs {
    const val SHOW_SUGGESTIONS = "show_suggestions"
    const val AUTOCORRECT = "autocorrect"
    const val LEARN_WORDS = "learn_words"

    /** Bumped by "Clear learned words" so the running keyboard drops its copy too. */
    const val LEARNED_RESET = "learned_reset"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}

fun SharedPreferences.showSuggestions() = getBoolean(Prefs.SHOW_SUGGESTIONS, true)
fun SharedPreferences.autocorrect() = getBoolean(Prefs.AUTOCORRECT, true)
fun SharedPreferences.learnWords() = getBoolean(Prefs.LEARN_WORDS, true)
fun SharedPreferences.learnedReset() = getInt(Prefs.LEARNED_RESET, 0)
