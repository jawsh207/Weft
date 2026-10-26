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
    interface Listener {
        fun onListeningChanged(listening: Boolean)
        fun onPartial(text: String)
        fun onFinal(text: String)
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
     * @param preferOnDevice use the engine's on-device mode when it offers one,
     *   keeping audio off the network. Falls back to the normal system engine.
     */
    fun start(preferOnDevice: Boolean = false) {
        stop()
        val r = if (preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(callbacks)
        recognizer = r

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

        listener.onListeningChanged(true)
        r.startListening(intent)
    }

    /** Ends listening; the engine still delivers final results for what was heard. */
    fun finish() {
        recognizer?.stopListening()
    }

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

    private fun bestResult(bundle: Bundle): String? =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
}
