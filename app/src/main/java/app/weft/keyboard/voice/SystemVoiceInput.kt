package app.weft.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Voice typing through whatever speech engine the user selected in system
 * settings. Nothing is bundled: audio goes only to that engine.
 *
 * Must be used from the main thread.
 */
class SystemVoiceInput(
    private val context: Context,
    private val listener: Listener,
) {
    /** What the keyboard hears back from the speech engine. */
    interface Listener {
        /** Listening started or ended, for the mic key and the status in the strip. */
        fun onListeningChanged(listening: Boolean)
        /** The words recognized so far; replaced as the engine refines them. */
        fun onPartial(text: String)
        /** The finished text for what was said. */
        fun onFinal(text: String)
        /** The engine gave up; [error] is one of SpeechRecognizer's ERROR_ codes. */
        fun onError(error: Int)
    }

    private var recognizer: SpeechRecognizer? = null

    /** False on devices with no speech engine installed (common on de-Googled ROMs). */
    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    val hasPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val isListening: Boolean
        get() = recognizer != null

    /**
     * @param preferOffline ask the user's chosen engine to recognize on the phone
     *   (RecognizerIntent.EXTRA_PREFER_OFFLINE). It's a request: engines without
     *   offline support ignore it. Weft never switches to a different engine.
     * @param search the field is a search box: ask for search-style recognition,
     *   which engines may tune for short queries.
     *
     * Weft doesn't ask for formatting (EXTRA_ENABLE_FORMATTING): it adds
     * punctuation and capitals itself (VoiceText), so plain text is ideal and
     * engines that format anyway work too. It does ask engines not to show a
     * trailing period in live results, so the preview doesn't flicker.
     */
    fun start(preferOffline: Boolean = false, search: Boolean = false) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(callbacks)
        recognizer = r

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                if (search) RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH else RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            .putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)

        listener.onListeningChanged(true)
        r.startListening(intent)
    }

    /** Ends listening; the engine still delivers final results for what was heard. */
    fun finish() {
        recognizer?.stopListening()
    }

    /** Stops at once and discards anything not yet delivered (the keyboard was put away). */
    fun stop() {
        val r = recognizer ?: return
        recognizer = null
        r.cancel()
        r.destroy()
        listener.onListeningChanged(false)
    }

    private val callbacks = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle) {
            bestResult(partialResults)?.let(listener::onPartial)
        }

        override fun onResults(results: Bundle) {
            listener.onFinal(bestResult(results).orEmpty())
            stop()
        }

        override fun onError(error: Int) {
            listener.onError(error)
            stop()
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** The engine's first (most likely) transcription, or null if it is empty. */
    private fun bestResult(bundle: Bundle): String? =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
}
