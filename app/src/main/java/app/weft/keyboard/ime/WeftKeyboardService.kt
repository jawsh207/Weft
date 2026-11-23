package app.weft.keyboard.ime

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
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
import app.weft.keyboard.predict.NextWordModel
import app.weft.keyboard.predict.Suggester
import app.weft.keyboard.predict.SwipeDecoder
import app.weft.keyboard.predict.TextContext
import app.weft.keyboard.settings.Prefs
import app.weft.keyboard.settings.autocorrect
import app.weft.keyboard.settings.emojiSuggestions
import app.weft.keyboard.settings.haptics
import app.weft.keyboard.settings.keyHeight
import app.weft.keyboard.settings.keySounds
import app.weft.keyboard.settings.numberRow
import app.weft.keyboard.settings.swipeVisuals
import app.weft.keyboard.settings.voiceOffline
import app.weft.keyboard.settings.learnWords
import app.weft.keyboard.settings.showSuggestions
import app.weft.keyboard.settings.swipeTyping
import app.weft.keyboard.settings.SettingsActivity
import app.weft.keyboard.theme.SystemTheme
import app.weft.keyboard.ui.EmojiActions
import app.weft.keyboard.ui.KeyboardScreen
import app.weft.keyboard.ui.ToolActions
import app.weft.keyboard.voice.SystemVoiceInput
import app.weft.keyboard.voice.VoiceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The keyboard itself: the Android service the system starts when Weft is the
 * selected keyboard. It shows the keys (drawn with Compose, see
 * [KeyboardScreen]), receives every key press, swipe, suggestion tap and voice
 * result, and writes the resulting text into whichever app's text field has
 * focus.
 *
 * What it does itself: decides which page to show for a field (letters, number
 * pad…), what the enter key does, shift and caps lock, backspace, the tools
 * row (select all, cut, copy, paste), the emoji search box, and starting
 * voice input. What it hands on: word
 * suggestions, autocorrect and learning go to [TypingController]; swipe paths
 * to [SwipeDecoder]; speech to [SystemVoiceInput].
 *
 * Things that are the way they are for a reason:
 *  - Compose expects to live in an Activity. A keyboard isn't one, so this
 *    service stands in as the lifecycle / saved-state / view-model owner.
 *  - Asking the app for its text (through the input connection) is a call into
 *    another process that can stall for seconds when that app is busy, a web
 *    page still loading, for instance. Nothing here asks the app anything
 *    before the keyboard is on screen, and slow answers are written to
 *    [KeyboardLog].
 *  - It never uses Android's full-screen "extract" mode: in landscape that mode
 *    replaces the app with a bare text box, and it makes Android ignore an
 *    app's request to show the keyboard unless the user tapped the field.
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

    /** What the keys on screen show; changing it redraws them. */
    private val state = KeyboardState()
    private val emojiHistory = EmojiHistory()
    private lateinit var voice: SystemVoiceInput
    private lateinit var learned: LearnedWords
    private lateinit var typing: TypingController
    /** When shift was last tapped, to spot a double tap (caps lock). */
    private var lastShiftTap = 0L
    /** A space to put before dictated text when the cursor follows a word, otherwise empty. */
    private var voicePrefix = ""

    /** The text before the cursor when voice input started, for capitals (see VoiceText). */
    private var voiceBefore: CharSequence? = null
    /** The suggestion ranking in progress, cancelled when the text changes again. */
    private var suggestJob: Job? = null
    /** The swipe preview being decoded, cancelled by the next one. */
    private var swipeJob: Job? = null

    /** The keys' view, kept to report its size in [KeyboardLog] when the window shows. */
    private var inputView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Reads the field's text for auto-capitals and suggestions. Posted from
     * onStartInputView rather than run in it, so the keyboard is on screen
     * before the app is asked anything (see the class description).
     */
    private val readField = Runnable {
        updateAutoCaps()
        refreshSuggestions()
    }

    @Volatile
    private var swipeDecoder: SwipeDecoder? = null
    /** Whether this field and the settings allow swipe typing (it also needs the dictionary loaded). */
    private var swipeAllowed = false
    private lateinit var clipboard: ClipboardManager
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { updateClipboardState() }

    /** Look-and-feel settings take effect at once, even if the keyboard is showing. */
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            Prefs.HAPTICS, Prefs.KEY_SOUNDS, Prefs.KEY_HEIGHT, Prefs.NUMBER_ROW, Prefs.SWIPE_VISUALS ->
                applyLookAndFeel(prefs)
        }
    }

    /** The current text field, for [TypingController]. */
    private val editor = object : TextEditor {
        override fun textBeforeCursor(length: Int): CharSequence? =
            timed("text before the cursor") { currentInputConnection?.getTextBeforeCursor(length, 0) }

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

    /** Runs a question to the app and notes it in [KeyboardLog] if the answer was slow. */
    private inline fun <T> timed(what: String, ask: () -> T): T {
        val start = SystemClock.uptimeMillis()
        val answer = ask()
        val took = SystemClock.uptimeMillis() - start
        if (took >= SLOW_REPLY_MS) KeyboardLog.note("the app took $took ms to answer ($what)")
        return answer
    }

    /**
     * The system started the keyboard: set up voice, learned words, the clipboard watcher, and
     * load the dictionary and emoji in the background.
     */
    override fun onCreate() {
        super.onCreate()
        KeyboardLog.attach(noBackupFilesDir)
        KeyboardLog.installCrashHandler()
        KeyboardLog.note("keyboard service started")
        savedStateController.performRestore(null)
        moveLifecycleTo(Lifecycle.State.CREATED)
        voice = SystemVoiceInput(this, voiceListener)
        learned = LearnedStore.get(this) // shared with Settings › Learned words
        typing = TypingController(learned)
        // Swipes the user kept teach the decoder their aim and their picks (on the phone only).
        typing.onSwipeKept = { kept, first, xs, ys -> swipeDecoder?.learnFrom(kept, first, xs, ys) }
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

    /** The emoji [word] names, in the skin tone last chosen for it. */
    private fun emojiFor(word: String): String? {
        val e = state.emojiCatalog?.suggestFor(word) ?: return null
        return state.emojiTones[e.base] ?: e.base
    }

    /** An emoji was tapped in the picker: type it and move it to the front of the recents. */
    private fun onEmoji(text: String) {
        state.message = null
        commitKeyText(text)
        rememberEmoji(text)
    }

    /** A skin tone was chosen from an emoji's long-press popup: remember it for that emoji and type it. */
    private fun onEmojiVariant(base: String, chosen: String) {
        emojiHistory.setPreferred(base, chosen)
        state.emojiTones = emojiHistory.preferredTones
        onEmoji(chosen)
    }

    /** Saves the recents and chosen skin tones so they survive a restart. */
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

    /**
     * Loads what the user has taught the keyboard and the phone's personal dictionary, then the
     * main dictionary.
     */
    private fun loadPrediction() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                learned.ensureLoaded()
                learned.setSystemWords(DictionaryLoader.systemUserWords(this@WeftKeyboardService))
            }
            loadDictionary(currentLocale())
        }
    }

    /** The dictionary for the selected keyboard language (English US, UK, Canada, Australia). */
    private var dictionaryLocale: String? = null
    private var dictionaryJob: Job? = null

    /**
     * Opens the dictionary for [locale] off the main thread and builds the suggester and swipe
     * decoder from it.
     */
    private fun loadDictionary(locale: String) {
        if (locale == dictionaryLocale) return
        dictionaryLocale = locale
        dictionaryJob?.cancel()
        dictionaryJob = lifecycleScope.launch {
            val suggester = withContext(Dispatchers.IO) {
                val dict = DictionaryLoader.load(this@WeftKeyboardService, locale)
                val centers = KeyGeometry.centers(Layouts.qwerty)
                swipeDecoder = SwipeDecoder(dict, centers, learned)
                Suggester(dict, KeyProximity(centers), learned, nextWordModel())
            }
            typing.suggester = suggester
            state.swipeEnabled = swipeAllowed && swipeDecoder != null
            refreshSuggestions()
        }
    }

    /** The English next-word model, opened on first use and shared by every English variant. */
    private var model: NextWordModel? = null
    private var modelLoaded = false

    /** Opens the next-word model the first time (off the main thread); null if the build has none. */
    @Synchronized
    private fun nextWordModel(): NextWordModel? {
        if (!modelLoaded) {
            model = DictionaryLoader.loadNextWordModel(this)
            modelLoaded = true
        }
        return model
    }

    /** The user switched keyboard language (US, UK, Canada, Australia): load that dictionary. */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: android.view.inputmethod.InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val tag = newSubtype?.languageTag?.takeIf { it.isNotEmpty() } ?: return
        if (dictionaryLocale != null) loadDictionary(tag.replace('-', '_'))
    }

    /** The selected keyboard language as a dictionary name ("en_GB"); US English if none is set. */
    private fun currentLocale(): String {
        val tag = getSystemService(InputMethodManager::class.java)
            ?.currentInputMethodSubtype?.languageTag
        return tag?.takeIf { it.isNotEmpty() }?.replace('-', '_') ?: "en_US"
    }

    // --- Showing and hiding ------------------------------------------------------------

    /** Never full-screen "extract" mode, in any orientation (see the class description). */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /** A text field got focus (the keyboard may or may not be shown for it). */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        KeyboardLog.note(
            "field focused in ${attribute?.packageName}: type 0x%x, options 0x%x%s".format(
                attribute?.inputType ?: 0, attribute?.imeOptions ?: 0, if (restarting) ", same field again" else "",
            ),
        )
    }

    override fun onFinishInput() {
        KeyboardLog.note("field left")
        super.onFinishInput()
    }

    /**
     * Android asks whether to show the keyboard. The answer is the system's own;
     * this only writes it down, with the usual reasons when the answer is no.
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        val show = super.onShowInputRequested(flags, configChange)
        val config = resources.configuration
        KeyboardLog.note(
            "show requested (flags $flags): " + if (show) "yes" else
                "NO. Hardware keyboard: ${config.keyboard != Configuration.KEYBOARD_NOKEYS}" +
                    " (hidden: ${config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES})" +
                    ", shown with one allowed: ${onEvaluateInputViewShown()}",
        )
        return show
    }

    override fun onWindowShown() {
        super.onWindowShown()
        KeyboardLog.note("window shown")
        // After layout: a keyboard that is "shown" but has no height looks like one that never opened.
        inputView?.post { KeyboardLog.note("keyboard size ${inputView?.width}x${inputView?.height}") }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        KeyboardLog.note("window hidden")
        lifecycleScope.launch(Dispatchers.IO) { KeyboardLog.save() }
    }

    /**
     * Builds the keys. Android calls this the first time the keyboard is shown and again after
     * rotation or a theme change.
     */
    override fun onCreateInputView(): View {
        KeyboardLog.note("building the keys")
        window.window?.decorView?.let { root ->
            root.setViewTreeLifecycleOwner(this)
            root.setViewTreeViewModelStoreOwner(this)
            root.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            inputView = this
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
                            onSelectAll = { editAction(android.R.id.selectAll) },
                            onCut = { editAction(android.R.id.cut) },
                            onCopy = { editAction(android.R.id.copy) },
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

    /**
     * The keyboard is about to appear for a field: pick the page and enter key for it, apply
     * settings, and reset anything left over from the last field.
     */
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        val began = SystemClock.uptimeMillis()
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
        // The field says up front whether it starts with a capital: no need to ask the app.
        if (state.shift != ShiftState.Locked) {
            val capital = info.initialCapsMode != 0 && state.page == KeyboardPage.Letters
            state.shift = if (capital) ShiftState.Once else ShiftState.Off
        }
        applyLookAndFeel(Prefs.of(this))
        configurePrediction(info)
        // Suggestions belong to the last field until this one's text has been read.
        suggestJob?.cancel()
        state.suggestions = emptyList()
        mainHandler.removeCallbacks(readField)
        mainHandler.post(readField)
        KeyboardLog.note("keyboard ready for ${info.packageName} in ${SystemClock.uptimeMillis() - began} ms")
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
        typing.emojiFor = if (prefs.emojiSuggestions()) ::emojiFor else null
        typing.autocorrectEnabled = policy.autocorrect
        typing.learningEnabled = policy.learning
        typing.capitalizeI = isText && !isPassword && !technical
        swipeAllowed = policy.swipe
        state.swipeEnabled = swipeAllowed && swipeDecoder != null
    }

    /** Copies the look-and-feel settings (vibration, sounds, key height, number row, swipe visuals) to the keys. */
    private fun applyLookAndFeel(prefs: android.content.SharedPreferences) {
        state.haptics = prefs.haptics()
        state.sounds = prefs.keySounds()
        state.keyHeight = prefs.keyHeight()
        state.numberRow = prefs.numberRow()
        state.swipeVisuals = prefs.swipeVisuals()
    }

    /**
     * The keyboard is being put away: stop listening, finish any half-entered voice text and save
     * what was learned.
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        KeyboardLog.note("keyboard put away")
        mainHandler.removeCallbacks(readField)
        voice.stop()
        suggestJob?.cancel()
        currentInputConnection?.finishComposingText()
        lifecycleScope.launch(Dispatchers.IO) { learned.save() }
        moveLifecycleTo(Lifecycle.State.STARTED)
        super.onFinishInputView(finishingInput)
    }

    /** Rotation, dark mode, wallpaper colors, font size and the like changed. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        KeyboardLog.note(
            "phone settings changed: " +
                (if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait") +
                ", hardware keyboard: ${newConfig.keyboard != Configuration.KEYBOARD_NOKEYS}",
        )
        state.themeEpoch++ // dark mode, palette, contrast or font changes
    }

    /**
     * The cursor moved or the text changed, by typing or by the user tapping in the field: refresh
     * auto-capitals and suggestions.
     */
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

    /** The system is shutting the keyboard down: save and release everything. */
    override fun onDestroy() {
        KeyboardLog.note("keyboard service stopped")
        mainHandler.removeCallbacks(readField)
        voice.stop()
        clipboard.removePrimaryClipChangedListener(clipListener)
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        // super.onDestroy() finishes the input view, which calls onFinishInputView,
        // so it has to run while the lifecycle is still alive.
        super.onDestroy()
        suggestJob?.cancel()
        learned.save()
        KeyboardLog.save()
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
     * or finished (type it). Points are in key units from [SwipeArea], [times]
     * when each was touched.
     */
    private fun onSwipe(xs: FloatArray, ys: FloatArray, times: LongArray, done: Boolean) {
        val decoder = swipeDecoder ?: return
        state.toolsOpen = false
        state.pendingRemoval = null
        val ctx = typing.context(editor)
        // Context for ranking: the word just before where this one will go.
        val previous = ctx.fragment.ifEmpty { null } ?: ctx.previous
        val before = if (ctx.fragment.isNotEmpty()) ctx.previous else ctx.before
        swipeJob?.cancel()
        if (done) {
            suggestJob?.cancel()
            val words = decoder.decode(xs, ys, previous, before = before, times = times).map { applyShift(it.word) }
            if (words.isEmpty()) return
            state.message = null
            typing.onSwipe(editor, words, xs, ys)
            if (state.shift == ShiftState.Once) state.shift = ShiftState.Off
            return
        }
        swipeJob = lifecycleScope.launch {
            val words = withContext(Dispatchers.Default) { decoder.decode(xs, ys, previous, before = before, times = times) }
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

    /** A suggestion in the strip was tapped. */
    private fun onSuggestion(chip: SuggestionChip) {
        state.message = null
        state.pendingRemoval = null
        typing.onPick(editor, chip)
        if (chip.style == ChipStyle.Emoji) rememberEmoji(chip.text)
        if (state.shift == ShiftState.Once) state.shift = ShiftState.Off
    }

    // --- Key handling --------------------------------------------------------------

    /**
     * A key was tapped. Letters and symbols are typed (capitalized if shift is on); action keys go
     * to [onAction].
     */
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

    /** Shift, backspace, enter, space and the keys that switch page. */
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

    /**
     * Enter: finish the current word, then do what the field asked for (search, send, next…) or
     * type a new line.
     */
    private fun onEnter() {
        val ic = currentInputConnection ?: return
        typing.onSeparator(editor, "") // autocorrect the last word first
        if (state.enterAction != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(state.enterAction)
        } else {
            sendKeyChar('\n')
        }
    }

    /** What the enter key should do in this field, or IME_ACTION_NONE for a plain new line. */
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

    /**
     * Turns shift on at the start of a sentence, where the field wants capitals (asks the app, so
     * not while the keyboard is opening).
     */
    private fun updateAutoCaps() {
        if (state.shift == ShiftState.Locked || state.page != KeyboardPage.Letters) return
        val ic = currentInputConnection ?: return
        val inputType = currentInputEditorInfo?.inputType ?: return
        val caps = timed("whether to capitalize") { ic.getCursorCapsMode(inputType) }
        state.shift = if (caps != 0) ShiftState.Once else ShiftState.Off
    }

    // --- Tools row --------------------------------------------------------------------

    /** Whether there is anything to paste, for the tools row. */
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

    /**
     * Tools row › Select all, Cut, Copy: asks the app to do it, exactly as its
     * own text menu would. The tools stay open, since these are often used one
     * after another (select all, then copy). Cut and copy do nothing when no
     * text is selected.
     */
    private fun editAction(id: Int) {
        val ic = currentInputConnection ?: return
        typing.onInputStarted() // the word being typed is no longer what's at the cursor
        ic.performContextMenuAction(id)
    }

    /** Tools row › Clear clipboard. */
    private fun clearClipboard() {
        state.toolsOpen = false
        runCatching { clipboard.clearPrimaryClip() }
        updateClipboardState()
        state.message = getString(R.string.clipboard_cleared)
    }

    /** Tools row › Settings: hide the keyboard and open Weft's settings. */
    private fun openSettings() {
        state.toolsOpen = false
        requestHideSelf(0)
        startActivity(
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    // --- Voice -------------------------------------------------------------------------

    /**
     * The mic key: stop if already listening, ask for the microphone permission if it's missing,
     * otherwise start the system's speech engine.
     */
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
                voiceBefore = currentInputConnection?.getTextBeforeCursor(VOICE_CONTEXT, 0)
                voice.start(preferOffline = Prefs.of(this).voiceOffline(), search = isSearchField())
            }
        }
    }

    /** Search boxes and address bars, where a voice query is a search. */
    private fun isSearchField(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return state.enterAction == EditorInfo.IME_ACTION_SEARCH ||
            variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_FILTER
    }

    /**
     * What the speech engine reports back: partial words appear as you speak, the final text
     * replaces them.
     */
    private val voiceListener = object : SystemVoiceInput.Listener {
        override fun onListeningChanged(listening: Boolean) {
            state.listening = listening
            if (!listening) refreshSuggestions()
        }

        override fun onPartial(text: String) {
            currentInputConnection?.setComposingText(voiceText(text), 1)
        }

        override fun onFinal(text: String) {
            val ic = currentInputConnection ?: return
            val out = voiceText(text)
            if (out.isEmpty()) ic.finishComposingText() else ic.commitText(out, 1)
        }

        /**
         * Punctuation only where it was spoken, capitals that fit the cursor's
         * place in the sentence, and a space before it unless it starts with a mark.
         */
        private fun voiceText(raw: String): String {
            val formatted = VoiceText.format(raw, voiceBefore) { typing.suggester?.keepsCapital(it) == true }
            if (formatted.isEmpty()) return ""
            return if (VoiceText.attachesToPrevious(formatted)) formatted else voicePrefix + formatted
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

        /** An app answering slower than this (ms) is worth a line in [KeyboardLog]. */
        const val SLOW_REPLY_MS = 150L
        const val GRAPHEME_LOOKBACK = 64
        const val VOICE_CONTEXT = 64
        const val EMOJI_ASSET = "emoji/emoji.txt"
        const val EMOJI_RECENTS = "emoji_recents"
        const val EMOJI_TONES = "emoji_tones"
    }
}
