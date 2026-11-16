package app.weft.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import app.weft.keyboard.ui.HapticLevel
import app.weft.keyboard.ui.KeyHeight
import app.weft.keyboard.ui.SoundLevel

/**
 * The names the settings are saved under, and how to read each one with its
 * default. The settings screens write them; the keyboard reads them. They are
 * kept in the app's private storage and never leave the phone.
 */
object Prefs {
    /** Typing: the suggestion strip, autocorrect, learning from typing, and swipe typing. */
    const val SHOW_SUGGESTIONS = "show_suggestions"
    const val AUTOCORRECT = "autocorrect"
    const val LEARN_WORDS = "learn_words"
    const val SWIPE = "swipe_typing"

    /** Bumped by "Clear learned words" so the running keyboard drops its copy too. */
    const val LEARNED_RESET = "learned_reset"
    /** Keys: vibration strength, click loudness, key height, and a row of digits above the letters. */
    const val HAPTICS = "haptics"
    const val KEY_SOUNDS = "key_sounds"
    const val KEY_HEIGHT = "key_height_v2"

    /** Where the key height was saved up to v1.0.0, under the old names (see [KeyHeight]). Only read. */
    const val KEY_HEIGHT_LEGACY = "key_height"
    const val NUMBER_ROW = "number_row"
    /** Voice: prefer on-device recognition. */
    const val VOICE_OFFLINE = "voice_offline"
    /** Offer an emoji in the strip for a word that names one. */
    const val EMOJI_SUGGESTIONS = "emoji_suggestions"
    /** Swipe › Enhanced visuals: tapered trail and lit letters, or a plain trail. */
    const val SWIPE_VISUALS = "swipe_visuals"

    /** The app's settings file. */
    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}

// One reader per setting, so its default lives in exactly one place.

/** Show the suggestion strip (on unless switched off). */
fun SharedPreferences.showSuggestions() = getBoolean(Prefs.SHOW_SUGGESTIONS, true)

/** Let space fix mistyped words (on unless switched off). */
fun SharedPreferences.autocorrect() = getBoolean(Prefs.AUTOCORRECT, true)

/** Learn new words and habits from typing (on unless switched off). */
fun SharedPreferences.learnWords() = getBoolean(Prefs.LEARN_WORDS, true)

/** Swipe typing (on unless switched off). */
fun SharedPreferences.swipeTyping() = getBoolean(Prefs.SWIPE, true)

/** How many times "Clear learned words" has been used; a change tells the keyboard to drop its copy. */
fun SharedPreferences.learnedReset() = getInt(Prefs.LEARNED_RESET, 0)

/** Vibration strength for key presses (Normal by default). */
fun SharedPreferences.haptics() = HapticLevel.of(getString(Prefs.HAPTICS, null))

/** Loudness of key clicks (Off by default). */
fun SharedPreferences.keySounds() = SoundLevel.of(getString(Prefs.KEY_SOUNDS, null))

/** Key height (Normal by default). */
fun SharedPreferences.keyHeight() =
    KeyHeight.of(getString(Prefs.KEY_HEIGHT, null), getString(Prefs.KEY_HEIGHT_LEGACY, null))

/** Digits above the letters (off by default). */
fun SharedPreferences.numberRow() = getBoolean(Prefs.NUMBER_ROW, false)

/** Prefer on-device speech recognition (off by default). */
fun SharedPreferences.voiceOffline() = getBoolean(Prefs.VOICE_OFFLINE, false)

/** Offer an emoji for a word that names one (on unless switched off). */
fun SharedPreferences.emojiSuggestions() = getBoolean(Prefs.EMOJI_SUGGESTIONS, true)

/** Enhanced swipe visuals (on unless switched off). */
fun SharedPreferences.swipeVisuals() = getBoolean(Prefs.SWIPE_VISUALS, true)
