package app.weft.keyboard.ime

import android.content.Intent
import android.content.res.Configuration
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.weft.keyboard.R
import app.weft.keyboard.layout.ActionKey
import app.weft.keyboard.layout.CharKey
import app.weft.keyboard.layout.Gap
import app.weft.keyboard.layout.Key
import app.weft.keyboard.layout.KeyAction
import app.weft.keyboard.layout.KeyGeometry
import app.weft.keyboard.layout.KeyboardPage
import app.weft.keyboard.layout.Layouts
import app.weft.keyboard.predict.KeyProximity
import app.weft.keyboard.predict.LearnedWords
import app.weft.keyboard.predict.Suggester
import app.weft.keyboard.predict.TextContext
import app.weft.keyboard.settings.Prefs
import app.weft.keyboard.settings.autocorrect
import app.weft.keyboard.settings.learnWords
import app.weft.keyboard.settings.learnedReset
import app.weft.keyboard.settings.showSuggestions
import app.weft.keyboard.settings.SettingsActivity
import app.weft.keyboard.theme.SystemTheme
import app.weft.keyboard.ui.KeyboardScreen
import app.weft.keyboard.voice.SystemVoiceInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The keyboard. Hosts Compose inside an InputMethodService, which needs the
 * service to act as the lifecycle / saved-state / view-model owner that an
 * Activity would normally provide.
 */
class WeftKeyboardService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // --- Compose hosting plumbing -------------------------------------------------

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    // --- Keyboard state ------------------------------------------------------------

    private val state = KeyboardState()
    private lateinit var voice: SystemVoiceInput
    private lateinit var learned: LearnedWords
    private lateinit var typing: TypingController
    private var lastShiftTap = 0L
    private var voicePrefix = ""
    private var suggestJob: Job? = null
    private var learnedResetSeen = 0

    /** The current text field, for [TypingController]. */
    private val editor = object : TextEditor {
        override fun textBeforeCursor(length: Int): CharSequence? =
            currentInputConnection?.getTextBeforeCursor(length, 0)

        override fun deleteBefore(length: Int) {
            currentInputConnection?.deleteSurroundingText(length, 0)
        }

        override fun insert(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        override fun batch(block: () -> Unit) {
            val ic = currentInputConnection
            ic?.beginBatchEdit()
            try {
                block()
            } finally {
                ic?.endBatchEdit()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        voice = SystemVoiceInput(this, voiceListener)
        learned = LearnedWords(File(filesDir, LEARNED_FILE))
        typing = TypingController(learned)
        loadPrediction()
    }

    private fun loadPrediction() {
        val locale = currentLocale()
        lifecycleScope.launch {
            val suggester = withContext(Dispatchers.IO) {
                learned.load()
                learnedResetSeen = Prefs.of(this@WeftKeyboardService).learnedReset()
                learned.setSystemWords(DictionaryLoader.systemUserWords(this@WeftKeyboardService))
                val dict = DictionaryLoader.load(this@WeftKeyboardService, locale)
                Suggester(dict, KeyProximity(KeyGeometry.centers(Layouts.qwerty)), learned)
            }
            typing.suggester = suggester
            refreshSuggestions()
        }
    }

    private fun currentLocale(): String {
        val tag = getSystemService(InputMethodManager::class.java)
            ?.currentInputMethodSubtype?.languageTag
        return tag?.takeIf { it.isNotEmpty() }?.replace('-', '_') ?: "en_US"
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { root ->
            root.setViewTreeLifecycleOwner(this)
            root.setViewTreeViewModelStoreOwner(this)
            root.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                SystemTheme(epoch = state.themeEpoch) {
                    KeyboardScreen(
                        state = state,
                        onKey = ::onKey,
                        onEmoji = ::commitKeyText,
                        onMic = ::onMic,
                        onSuggestion = ::onSuggestion,
                    )
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        state.themeEpoch++ // re-read the palette each time the keyboard shows
        state.message = null
        state.voiceAvailable = voice.isAvailable
        state.enterAction = resolveEnterAction(info)
        state.page = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> KeyboardPage.Symbols
            else -> KeyboardPage.Letters
        }
        if (state.shift != ShiftState.Locked) state.shift = ShiftState.Off
        configurePrediction(info)
        updateAutoCaps()
        refreshSuggestions()
    }

    /** Applies user settings and what the field allows (passwords, incognito, URLs…). */
    private fun configurePrediction(info: EditorInfo) {
        val prefs = Prefs.of(this)
        val type = info.inputType
        val isText = (type and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
        val variation = type and InputType.TYPE_MASK_VARIATION
        val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val noSuggestions = (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val technical = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_FILTER
        val noLearning = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        typing.suggestionsEnabled = prefs.showSuggestions() && isText && !isPassword && !noSuggestions
        typing.autocorrectEnabled = prefs.autocorrect() && !technical
        typing.learningEnabled = prefs.learnWords() && !isPassword && !noLearning

        val reset = prefs.learnedReset()
        if (reset != learnedResetSeen) {
            learnedResetSeen = reset
            learned.clear()
        }
        typing.onInputStarted()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        voice.stop()
        suggestJob?.cancel()
        currentInputConnection?.finishComposingText()
        lifecycleScope.launch(Dispatchers.IO) { learned.save() }
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onFinishInputView(finishingInput)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        state.themeEpoch++ // dark mode, palette, contrast or font changes
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        if (!state.listening) {
            updateAutoCaps()
            refreshSuggestions()
        }
    }

    override fun onDestroy() {
        voice.stop()
        learned.save()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    // --- Suggestions -------------------------------------------------------------------

    /** Reads the text before the cursor here, then ranks words off the main thread. */
    private fun refreshSuggestions() {
        suggestJob?.cancel()
        if (!typing.suggestionsEnabled || typing.suggester == null) {
            state.suggestions = emptyList()
            return
        }
        val ctx = typing.context(editor)
        suggestJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { typing.compute(ctx) }
            state.suggestions = typing.publish(result)
        }
    }

    private fun onSuggestion(chip: SuggestionChip) {
        state.message = null
        typing.onPick(editor, chip)
        if (state.shift == ShiftState.Once) state.shift = ShiftState.Off
    }

    // --- Key handling --------------------------------------------------------------

    private fun onKey(key: Key) {
        state.message = null
        when (key) {
            is CharKey -> {
                val text = if (state.shift != ShiftState.Off) key.output.uppercase() else key.output
                commitKeyText(text)
                if (state.shift == ShiftState.Once) state.shift = ShiftState.Off
            }
            is ActionKey -> onAction(key.action)
            is Gap -> Unit
        }
    }

    private fun onAction(action: KeyAction) {
        when (action) {
            KeyAction.Shift -> toggleShift()
            KeyAction.Backspace -> if (!typing.onBackspace(editor)) deleteBackward()
            KeyAction.Enter -> onEnter()
            KeyAction.Space -> typing.onSeparator(editor, " ")
            KeyAction.ToLetters -> state.page = KeyboardPage.Letters
            KeyAction.ToSymbols -> state.page = KeyboardPage.Symbols
            KeyAction.ToMoreSymbols -> state.page = KeyboardPage.MoreSymbols
            KeyAction.Emoji -> state.page = KeyboardPage.Emoji
        }
    }

    /** Punctuation and emoji end the current word (and may autocorrect it). */
    private fun commitKeyText(text: String) {
        if (TextContext.isSeparator(text)) typing.onSeparator(editor, text) else typing.onText(editor, text)
    }

    /** Off → Once → Off; a quick double tap locks caps. */
    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        state.shift = when {
            state.shift == ShiftState.Locked -> ShiftState.Off
            now - lastShiftTap < DOUBLE_TAP_MS -> ShiftState.Locked
            state.shift == ShiftState.Off -> ShiftState.Once
            else -> ShiftState.Off
        }
        lastShiftTap = now
    }

    /** Deletes one whole grapheme, so ZWJ emoji and flags go in one press. */
    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(GRAPHEME_LOOKBACK, 0)
        if (before.isNullOrEmpty()) {
            // Nothing visible to delete; let the app handle a raw DEL (e.g. chip fields).
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        val text = before.toString()
        val breaker = BreakIterator.getCharacterInstance().apply { setText(text) }
        val start = breaker.preceding(text.length)
        val length = if (start == BreakIterator.DONE) 1 else text.length - start
        ic.deleteSurroundingText(length, 0)
    }

    private fun onEnter() {
        val ic = currentInputConnection ?: return
        typing.onSeparator(editor, "") // autocorrect the last word first
        if (state.enterAction != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(state.enterAction)
        } else {
            sendKeyChar('\n')
        }
    }

    private fun resolveEnterAction(info: EditorInfo): Int {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
            return EditorInfo.IME_ACTION_NONE
        }
        return when (val action = info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS -> action
            else -> EditorInfo.IME_ACTION_NONE
        }
    }

    private fun updateAutoCaps() {
        if (state.shift == ShiftState.Locked || state.page != KeyboardPage.Letters) return
        val ic = currentInputConnection ?: return
        val inputType = currentInputEditorInfo?.inputType ?: return
        state.shift = if (ic.getCursorCapsMode(inputType) != 0) ShiftState.Once else ShiftState.Off
    }

    // --- Voice -------------------------------------------------------------------------

    private fun onMic() {
        state.message = null
        when {
            voice.isListening -> voice.finish()
            !voice.hasPermission -> startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(SettingsActivity.EXTRA_REQUEST_MIC, true),
            )
            else -> {
                typing.onInputStarted()
                val before = currentInputConnection?.getTextBeforeCursor(1, 0)
                voicePrefix = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else ""
                voice.start()
            }
        }
    }

    private val voiceListener = object : SystemVoiceInput.Listener {
        override fun onListeningChanged(listening: Boolean) {
            state.listening = listening
            if (!listening) refreshSuggestions()
        }

        override fun onPartial(text: String) {
            currentInputConnection?.setComposingText(voicePrefix + text, 1)
        }

        override fun onFinal(text: String) {
            val ic = currentInputConnection ?: return
            if (text.isEmpty()) ic.finishComposingText() else ic.commitText(voicePrefix + text, 1)
        }

        override fun onError(error: Int) {
            currentInputConnection?.finishComposingText()
            state.message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.voice_no_match)
                SpeechRecognizer.ERROR_CLIENT -> null // we cancelled it ourselves
                else -> getString(R.string.voice_error, error)
            }
        }
    }

    private companion object {
        const val DOUBLE_TAP_MS = 300L
        const val GRAPHEME_LOOKBACK = 64
        const val LEARNED_FILE = "learned_words.tsv"
    }
}
