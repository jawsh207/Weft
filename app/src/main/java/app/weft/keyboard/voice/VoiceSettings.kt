package app.weft.keyboard.voice

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** Where the system's default speech engine is chosen. */
object VoiceSettings {

    /**
     * Opens the system Languages page (Language & region on newer releases),
     * whose Speech section holds the Voice input picker.
     *
     * Android has no public shortcut to that picker. ACTION_VOICE_INPUT_SETTINGS
     * is not it: on AOSP, GrapheneOS and Pixels it opens the default digital
     * assistant picker, which lists only full assistant apps. The Languages page
     * is opened through Settings' exported "android.settings.LANGUAGE_SETTINGS"
     * action, with the Voice input row highlighted where Settings supports that,
     * then ACTION_LOCALE_SETTINGS, then Settings itself.
     */
    fun openVoiceInputSettings(context: Context) {
        val highlight = Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, VOICE_INPUT_PREFERENCE) }
        val candidates = listOf(
            Intent(ACTION_LANGUAGE_SETTINGS)
                .putExtra(EXTRA_FRAGMENT_ARG_KEY, VOICE_INPUT_PREFERENCE)
                .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, highlight),
            Intent(Settings.ACTION_LOCALE_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    /** Exported by AOSP Settings for its Languages page; not in the public SDK. */
    private const val ACTION_LANGUAGE_SETTINGS = "android.settings.LANGUAGE_SETTINGS"

    /** Settings' extras for scrolling to and highlighting a row. */
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    private const val VOICE_INPUT_PREFERENCE = "voice_input_settings"
}
