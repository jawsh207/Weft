package app.weft.keyboard.ime

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
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
import app.weft.keyboard.emoji.EmojiCatalog
import app.weft.keyboard.emoji.EmojiHistory
import app.weft.keyboard.emoji.SystemEmoji
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
import app.weft.keyboard.predict.SwipeDecoder
import app.weft.keyboard.predict.TextContext
import app.weft.keyboard.settings.Prefs
import app.weft.keyboard.settings.autocorrect
import app.weft.keyboard.settings.learnWords
import app.weft.keyboard.settings.learnedReset
import app.weft.keyboard.settings.showSuggestions
import app.weft.keyboard.settings.swipeTyping
import app.weft.keyboard.settings.SettingsActivity
import app.weft.keyboard.theme.SystemTheme
import app.weft.keyboard.ui.EmojiActions
import app.weft.keyboard.ui.KeyboardScreen
import app.weft.keyboard.ui.ToolActions
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
    private val emojiHistory = EmojiHistory()
    private lateinit var voice: SystemVoiceInput
    private lateinit var learned: LearnedWords
    private lateinit var typing: TypingController
    private var lastShiftTap = 0L
    private var voicePrefix = ""
    private var suggestJob: Job? = null
    private var swipeJob: Job? = null

    @Volatile
    private var swipeDecoder: SwipeDecoder? = null
    private var swipeAllowed = false
    private var learnedResetSeen = 0
    private lateinit var clipboard: ClipboardManager
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { updateClipboardState() }

    /** "Clear" in settings takes effect at once, even if the keyboard is showing. */
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == Prefs.LEARNED_RESET) applyLearnedReset(prefs.learnedReset())
    }

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
        moveLifecycleTo(Lifecycle.State.CREATED)
        voice = SystemVoiceInput(this, voiceListener)
        learned = LearnedWords(
            File(filesDir, LearnedWords.WORDS_FILE),
            File(filesDir, LearnedWords.HISTORY_FILE),
        )
        typing = TypingController(learned)
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.addPrimaryClipChangedListener(clipListener)
        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefsListener)
        loadPrediction()
        loadEmoji()
    }

    /** Emoji list from assets, filtered to what the phone's emoji font can draw. */
    private fun loadEmoji() {
        val prefs = Prefs.of(this)
        emojiHistory.decode(prefs.getString(EMOJI_RECENTS, null), prefs.getString(EMOJI_TONES, null))
        state.emojiRecents = emojiHistory.recents
        state.emojiTones = emojiHistory.preferredTones
        lifecycleScope.launch {
            state.emojiCatalog = withContext(Dispatchers.Default) {
                val paint = Paint()
                runCatching {
                    val text = assets.open(EMOJI_ASSET).bufferedReader().use { it.readText() }
                    EmojiCatalog.parse(text) { paint.hasGlyph(it) }
                }.getOrNull()?.takeIf { it.size > 0 }
                    ?: EmojiCatalog.fromList("Smileys & Emotion", SystemEmoji.starter)
            }
        }
    }

    private fun onEmoji(text: String) {
        state.message = null
        commitKeyText(text)
        rememberEmoji(text)
    }

    private fun onEmojiVariant(base: String, chosen: String) {
        emojiHistory.setPreferred(base, chosen)
        state.emojiTones = emojiHistory.preferredTones
        onEmoji(chosen)
    }

    private fun rememberEmoji(text: String) {
        emojiHistory.use(text)
        state.emojiRecents = emojiHistory.recents
        Prefs.of(this).edit()
            .putString(EMOJI_RECENTS, emojiHistory.encodeRecents())
            .putString(EMOJI_TONES, emojiHistory.encodeTones())
            .apply()
    }

    /** While searching emoji, keys edit the search box instead of the app's text. */
    private fun onEmojiSearchKey(key: Key) {
        val query = state.emojiQuery ?: return
        when (key) {
            is CharKey -> state.emojiQuery = query + key.output.lowercase()
            is ActionKey -> when (key.action) {
                KeyAction.Backspace -> state.emojiQuery = query.dropLast(1)
                KeyAction.Space -> if (query.isNotEmpty() && !query.endsWith(" ")) state.emojiQuery = "$query "
                KeyAction.Enter, KeyAction.Emoji -> state.emojiQuery = null
                KeyAction.ToLetters -> {
                    state.emojiQuery = null
                    state.page = KeyboardPage.Letters
                }
                KeyAction.Shift -> toggleShift()
                else -> Unit
            }
            is Gap -> Unit
        }
    }

    private fun loadPrediction() {
        val locale = currentLocale()
        lifecycleScope.launch {
            val suggester = withContext(Dispatchers.IO) {
                learned.load()
                learnedResetSeen = Prefs.of(this@WeftKeyboardService).learnedReset()
                learned.setSystemWords(DictionaryLoader.systemUserWords(this@WeftKeyboardService))
                val dict = DictionaryLoader.load(this@WeftKeyboardService, locale)
                val centers = KeyGeometry.centers(Layouts.qwerty)
                swipeDecoder = SwipeDecoder(dict, centers, learned)
                Suggester(dict, KeyProximity(centers), learned)
            }
            typing.suggester = suggester
            state.swipeEnabled = swipeAllowed && swipeDecoder != null
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
                        emoji = EmojiActions(
                            onEmoji = ::onEmoji,
                            onVariant = ::onEmojiVariant,
                            onSearch = { state.emojiQuery = "" },
                        ),
                        tools = ToolActions(
                            onToggle = { state.toolsOpen = !state.toolsOpen },
                            onPaste = ::paste,
                            onClearClipboard = ::clearClipboard,
                            onSettings = ::openSettings,
                        ),
                        onMic = ::onMic,
                        onSuggestion = ::onSuggestion,
                        onRemoveSuggestion = ::onRemoveSuggestion,
                        onAlternate = ::onAlternate,
                        onCursor = ::moveCursor,
                        onSwipe = ::onSwipe,
                    )
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        moveLifecycleTo(Lifecycle.State.RESUMED)

        state.themeEpoch++ // re-read the palette each time the keyboard shows
        state.message = null
        state.emojiQuery = null
        state.toolsOpen = false
        state.pendingRemoval = null
        updateClipboardState()
        state.voiceAvailable = voice.isAvailable
        state.enterAction = resolveEnterAction(info)
        state.page = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE -> KeyboardPage.Numpad
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
        typing.onInputStarted() // finish up the last field under its own settings
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

        val policy = FieldPolicy.of(
            FieldTraits(isText, isPassword, noSuggestions, technical, noLearning),
            TypingPrefs(prefs.showSuggestions(), prefs.autocorrect(), prefs.learnWords(), prefs.swipeTyping()),
        )
        typing.suggestionsEnabled = policy.suggestions
        typing.autocorrectEnabled = policy.autocorrect
        typing.learningEnabled = policy.learning
        swipeAllowed = policy.swipe
        state.swipeEnabled = swipeAllowed && swipeDecoder != null

        applyLearnedReset(prefs.learnedReset())
    }

    private fun applyLearnedReset(reset: Int) {
        if (reset == learnedResetSeen) return
        learnedResetSeen = reset
        learned.clear()
        lifecycleScope.launch(Dispatchers.IO) { learned.save() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        voice.stop()
        suggestJob?.cancel()
        currentInputConnection?.finishComposingText()
        lifecycleScope.launch(Dispatchers.IO) { learned.save() }
        moveLifecycleTo(Lifecycle.State.STARTED)
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
        clipboard.removePrimaryClipChangedListener(clipListener)
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        // super.onDestroy() finishes the input view, which calls onFinishInputView,
        // so it has to run while the lifecycle is still alive.
        super.onDestroy()
        suggestJob?.cancel()
        learned.save()
        moveLifecycleTo(Lifecycle.State.DESTROYED)
        store.clear()
    }

    /**
     * Lifecycle changes arrive from several system callbacks, sometimes during
     * teardown. Once destroyed, the lifecycle can't move again, so ignore them.
     */
    private fun moveLifecycleTo(state: Lifecycle.State) {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = state
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

    /**
     * A swipe in progress ([done] = false: preview the best guess in the strip)
     * or finished (type it). Points are in key units from [SwipeArea].
     */
    private fun onSwipe(xs: FloatArray, ys: FloatArray, done: Boolean) {
        val decoder = swipeDecoder ?: return
        state.toolsOpen = false
        state.pendingRemoval = null
        val ctx = typing.context(editor)
        // Context for ranking: the word just before where this one will go.
        val previous = ctx.fragment.ifEmpty { null } ?: ctx.previous
        swipeJob?.cancel()
        if (done) {
            suggestJob?.cancel()
            val words = decoder.decode(xs, ys, previous).map { applyShift(it.word) }
            if (words.isEmpty()) return
            state.message = null
            typing.onSwipe(editor, words)
            if (state.shift == ShiftState.Once) state.shift = ShiftState.Off
            return
        }
        swipeJob = lifecycleScope.launch {
            val words = withContext(Dispatchers.Default) { decoder.decode(xs, ys, previous) }
                .map { applyShift(it.word) }
            if (words.isNotEmpty()) {
                state.suggestions = listOf(
                    words.getOrNull(1)?.let { SuggestionChip(it) },
                    SuggestionChip(words[0], ChipStyle.Autocorrect),
                    words.getOrNull(2)?.let { SuggestionChip(it) },
                )
            }
        }
    }

    /** Long-press › Remove on a suggestion. */
    private fun onRemoveSuggestion(word: String) {
        state.pendingRemoval = null
        typing.onRemove(word)
        lifecycleScope.launch(Dispatchers.IO) { learned.save() }
        refreshSuggestions()
    }

    /** Shift capitalizes a swiped word; caps lock uppercases it. */
    private fun applyShift(word: String): String = when (state.shift) {
        ShiftState.Off -> word
        ShiftState.Once -> word.replaceFirstChar { it.titlecase() }
        ShiftState.Locked -> word.uppercase()
    }

    private fun onSuggestion(chip: SuggestionChip) {
        state.message = null
        state.pendingRemoval = null
        typing.onPick(editor, chip)
        if (state.shift == ShiftState.Once) state.shift = ShiftState.Off
    }

    // --- Key handling --------------------------------------------------------------

    private fun onKey(key: Key) {
        state.message = null
        state.toolsOpen = false
        state.pendingRemoval = null
        if (state.emojiQuery != null && state.page == KeyboardPage.Emoji) {
            onEmojiSearchKey(key)
            return
        }
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
            KeyAction.ToLetters -> {
                state.emojiQuery = null
                state.page = KeyboardPage.Letters
            }
            KeyAction.ToSymbols -> state.page = KeyboardPage.Symbols
            KeyAction.ToMoreSymbols -> state.page = KeyboardPage.MoreSymbols
            KeyAction.ToNumpad -> state.page = KeyboardPage.Numpad
            KeyAction.Emoji -> state.page = KeyboardPage.Emoji
        }
    }

    /** A long-press alternate ("é", "…") is typed like the key it came from. */
    private fun onAlternate(text: String) {
        state.message = null
        if (state.emojiQuery != null && state.page == KeyboardPage.Emoji) {
            state.emojiQuery = state.emojiQuery + text.lowercase()
            return
        }
        commitKeyText(text)
        if (state.shift == ShiftState.Once) state.shift = ShiftState.Off
    }

    /** Space-bar slide: move the cursor [steps] characters (negative = left). */
    private fun moveCursor(steps: Int) {
        if (steps == 0) return
        typing.onInputStarted() // the word being typed is no longer the one at the cursor
        val key = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(key) }
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

    // --- Tools row --------------------------------------------------------------------

    private fun updateClipboardState() {
        state.clipboardHasContent = runCatching { clipboard.hasPrimaryClip() }.getOrDefault(false)
    }

    /**
     * Lets the app paste, so it keeps formatting and can accept images; falls
     * back to typing the clip's text for apps that don't handle the paste action.
     */
    private fun paste() {
        state.toolsOpen = false
        val ic = currentInputConnection ?: return
        typing.onInputStarted()
        if (ic.performContextMenuAction(android.R.id.paste)) return
        val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(this) }.getOrNull()
        if (!text.isNullOrEmpty()) ic.commitText(text, 1)
    }

    private fun clearClipboard() {
        state.toolsOpen = false
        runCatching { clipboard.clearPrimaryClip() }
        updateClipboardState()
        state.message = getString(R.string.clipboard_cleared)
    }

    private fun openSettings() {
        state.toolsOpen = false
        requestHideSelf(0)
        startActivity(
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
        const val EMOJI_ASSET = "emoji/emoji.txt"
        const val EMOJI_RECENTS = "emoji_recents"
        const val EMOJI_TONES = "emoji_tones"
    }
}
