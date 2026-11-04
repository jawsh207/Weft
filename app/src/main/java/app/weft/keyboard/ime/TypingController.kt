package app.weft.keyboard.ime

import app.weft.keyboard.predict.LearnedWords
import app.weft.keyboard.predict.SuggestionResult
import app.weft.keyboard.predict.Suggester
import app.weft.keyboard.predict.TextContext
import app.weft.keyboard.predict.WordContext

/** The text field, as seen by [TypingController]. Implemented over InputConnection. */
interface TextEditor {
    fun textBeforeCursor(length: Int): CharSequence?
    fun deleteBefore(length: Int)
    fun insert(text: String)

    /** Groups edits so the app sees one change. */
    fun batch(block: () -> Unit) = block()
}

enum class ChipStyle {
    Normal,

    /** What the space bar will insert (autocorrect). Shown emphasized in the center. */
    Autocorrect,

    /** The literal typed text, so the user can keep it as-is. */
    Verbatim,

    /** An emoji for the word just typed ("pizza" → 🍕); picking it adds it after the word. */
    Emoji,
}

data class SuggestionChip(val text: String, val style: ChipStyle = ChipStyle.Normal)

/**
 * Word-level typing behavior, independent of Android so it can be unit tested:
 * autocorrect when a word ends, backspace to undo an autocorrect, smart spacing
 * around punctuation after a picked suggestion, and learning from what the user
 * types (new words, how often they use words, which word follows which).
 */
class TypingController(private val learned: LearnedWords) {

    /** Set once the dictionary has loaded; null until then. */
    @Volatile
    var suggester: Suggester? = null

    var suggestionsEnabled = true
    var autocorrectEnabled = true

    /** False in fields that ask for no personalized learning (e.g. incognito). */
    var learningEnabled = true

    /**
     * Capitalize "i" ("i'm", "i'll", …) when the word ends. A grammar rule like the
     * capital at the start of a sentence, not a guess at a typo, so it works even
     * where autocorrect is off. False in passwords, emails and web addresses.
     */
    var capitalizeI = true

    /** The emoji a word names, in the user's skin tone, or null (set by the keyboard; off in settings). */
    @Volatile
    var emojiFor: ((String) -> String?)? = null

    private data class Correction(
        val original: String,
        val corrected: String,
        val separator: String,
        val previous: String?,
    )

    private var lastCorrection: Correction? = null
    private var autoSpaced = false

    /**
     * The last swiped word, the decoder's other guesses for that swipe, and the
     * word before it. It's learned once the user moves on, since they may still
     * swap it for a guess or delete it.
     */
    private data class Swiped(val word: String, val alternatives: List<String>, val previous: String?)

    private var swiped: Swiped? = null

    /** After a swipe, the next tapped letter starts a new word with a space. */
    private var phantomSpace = false
    /** Undone corrections this session (also remembered for good when learning is allowed). */
    private val rejected = HashSet<String>()

    @Volatile
    private var lastResult: SuggestionResult? = null

    /** A new field, or the cursor moved: the word being typed is no longer the one at the cursor. */
    fun onInputStarted() {
        lastCorrection = null
        autoSpaced = false
        lastResult = null
        finishSwipe()
        lastWord = null
        phantomSpace = false
    }

    fun context(editor: TextEditor): WordContext =
        TextContext.parse(editor.textBeforeCursor(CONTEXT_CHARS))

    // --- Suggestions --------------------------------------------------------------------

    /** Safe to call off the main thread. */
    fun compute(ctx: WordContext): SuggestionResult {
        val s = suggester
        if (!suggestionsEnabled || s == null) return SuggestionResult.EMPTY
        return s.suggest(ctx.fragment, ctx.previous, before = ctx.before)
    }

    /** Remembers [result] (so space can reuse it) and returns chips for the strip. */
    fun publish(result: SuggestionResult): List<SuggestionChip?> {
        lastResult = result
        return chips(result)
    }

    /**
     * Three slots. While typing, the middle slot is always exactly what space
     * will insert: the correction or completion if there is one, otherwise the
     * word as typed.
     */
    fun chips(result: SuggestionResult): List<SuggestionChip?> {
        if (!suggestionsEnabled) return emptyList()
        val words = wordChips(result)
        // The word being typed (or what space will make of it), else the one just finished.
        val named = result.autocorrect?.takeIf { autocorrectEnabled }?.word
            ?: result.typed.ifEmpty { null } ?: lastWord
        val emoji = named?.let { emojiFor?.invoke(it) } ?: return words
        val slots = if (words.isEmpty()) listOf(null, null, null) else words
        return slots.take(2) + SuggestionChip(emoji, ChipStyle.Emoji)
    }

    /** The last finished word, for an emoji after it ("I love pizza " → 🍕). */
    @Volatile
    private var lastWord: String? = null

    private fun wordChips(result: SuggestionResult): List<SuggestionChip?> {
        val words = result.suggestions.map { it.word }
        val swipe = swiped
        if (swipe != null && result.typed == swipe.word) {
            // Right after a swipe: the word in the middle, the decoder's other guesses beside it.
            val others = swipe.alternatives.filter { it != swipe.word }
            return listOf(
                others.getOrNull(0)?.let { SuggestionChip(it) },
                SuggestionChip(swipe.word),
                others.getOrNull(1)?.let { SuggestionChip(it) },
            )
        }
        if (result.typed.isEmpty()) return centered(words.map { SuggestionChip(it) })

        val auto = capitalI(result.typed)?.takeIf { capitalizeI }
            ?: result.autocorrect?.takeIf { autocorrectEnabled && !isKeptAsTyped(result.typed) }?.word
        if (auto != null) {
            val alt = words.firstOrNull { it != auto }?.let { SuggestionChip(it) }
            return listOf(
                SuggestionChip(result.typed, ChipStyle.Verbatim),
                SuggestionChip(auto, ChipStyle.Autocorrect),
                alt,
            )
        }
        // Space keeps the typed word, so it goes in the middle.
        val typedStyle = if (result.typedIsKnown) ChipStyle.Normal else ChipStyle.Verbatim
        return listOf(
            words.getOrNull(0)?.let { SuggestionChip(it) },
            SuggestionChip(result.typed, typedStyle),
            words.getOrNull(1)?.let { SuggestionChip(it) },
        )
    }

    /** Best in the middle, like most keyboards: [2nd, 1st, 3rd]. */
    private fun centered(chips: List<SuggestionChip>): List<SuggestionChip?> =
        if (chips.isEmpty()) emptyList() else listOf(chips.getOrNull(1), chips[0], chips.getOrNull(2))

    // --- Editing ---------------------------------------------------------------------------

    /** Letters, digits, emoji: anything that continues or starts a word. */
    fun onText(editor: TextEditor, text: String) {
        lastCorrection = null
        autoSpaced = false
        finishSwipe()
        lastWord = null
        if (phantomSpace) {
            phantomSpace = false
            editor.insert(" $text")
        } else {
            editor.insert(text)
        }
    }

    /**
     * Types a swiped word: [candidates] best first, already in the right case.
     * Adds a space before it if it would otherwise join the previous word, and
     * remembers it so delete can remove it whole and the strip can offer the
     * other guesses.
     */
    fun onSwipe(editor: TextEditor, candidates: List<String>) {
        val word = candidates.firstOrNull() ?: return
        lastCorrection = null
        autoSpaced = false
        finishSwipe()
        val ctx = context(editor)
        val before = editor.textBeforeCursor(1)?.toString().orEmpty()
        val needsSpace = before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in OPENERS
        editor.insert(if (needsSpace) " $word" else word)
        // The word this one follows: the one right before the cursor, or the one before a space.
        val previous = if (needsSpace) ctx.fragment.ifEmpty { null } else ctx.previous
        swiped = Swiped(word, candidates, previous)
        phantomSpace = true
    }

    /**
     * Ends the current word with [separator] (" ", ",", "\n", …), autocorrecting
     * it first if appropriate. An empty [separator] just finishes the word,
     * e.g. before an editor action like Send.
     */
    fun onSeparator(editor: TextEditor, separator: String) {
        lastCorrection = null
        swiped = null
        phantomSpace = false
        if (autoSpaced) {
            autoSpaced = false
            val endsWithSpace = editor.textBeforeCursor(1)?.toString() == " "
            if (endsWithSpace && separator == " ") return // no double space after a pick
            if (endsWithSpace && separator in ATTACHING_PUNCTUATION) {
                // "keyboard |" + "." → "keyboard. "
                editor.batch {
                    editor.deleteBefore(1)
                    editor.insert("$separator ")
                }
                autoSpaced = true
                return
            }
        }

        val ctx = context(editor)
        val fix = autocorrectFor(ctx)
        if (fix != null) {
            editor.batch {
                editor.deleteBefore(ctx.fragment.length)
                editor.insert(fix + separator)
            }
            lastCorrection = Correction(ctx.fragment, fix, separator, ctx.previous)
            remember(fix, ctx.previous)
        } else {
            learn(ctx.fragment)
            remember(ctx.fragment, ctx.previous)
            if (separator.isNotEmpty()) editor.insert(separator)
        }
    }

    /**
     * Undoes the last autocorrect if backspace is pressed right after it.
     * Returns true if it did; otherwise the caller deletes normally.
     */
    fun onBackspace(editor: TextEditor): Boolean {
        lastWord = null
        val c = lastCorrection
        lastCorrection = null
        autoSpaced = false
        phantomSpace = false
        val swipe = swiped
        swiped = null
        if (swipe != null && editor.textBeforeCursor(swipe.word.length)?.toString() == swipe.word) {
            // A swiped word goes in one press, like it came in.
            editor.deleteBefore(swipe.word.length)
            return true
        }
        if (c == null) return false
        val tail = c.corrected + c.separator
        if (editor.textBeforeCursor(tail.length)?.toString() != tail) return false
        editor.batch {
            editor.deleteBefore(tail.length)
            editor.insert(c.original + c.separator)
        }
        // Don't fight the user on this word again; but "i" → "I" is grammar, so undoing
        // it only counts this once (it's often just backing up to type "I'm").
        if (capitalI(c.original) == null) keepAsTyped(c.original)
        learn(c.original)
        if (learningEnabled) {
            forgetPhrase(c.corrected, c.previous)
            remember(c.original, c.previous)
        }
        return true
    }

    /** Replaces the word being typed with [chip] and adds a space. */
    fun onPick(editor: TextEditor, chip: SuggestionChip) {
        if (chip.style == ChipStyle.Emoji) return pickEmoji(editor, chip.text)
        val ctx = context(editor)
        lastCorrection = null
        val swipe = swiped
        if (swipe != null && ctx.fragment == swipe.word) {
            // Swap the swiped word for another guess; keep the other guesses on offer.
            editor.batch {
                editor.deleteBefore(swipe.word.length)
                editor.insert(chip.text)
            }
            swiped = swipe.copy(word = chip.text)
            phantomSpace = true
            return
        }
        finishSwipe()
        phantomSpace = false
        editor.batch {
            if (ctx.fragment.isNotEmpty()) editor.deleteBefore(ctx.fragment.length)
            editor.insert(chip.text + " ")
        }
        autoSpaced = true
        if (chip.style == ChipStyle.Verbatim) {
            keepAsTyped(chip.text)
            if (learningEnabled) {
                learned.restore(chip.text)
                learned.recordUse(chip.text, explicit = true)
            }
        }
        // It replaces the word being typed (if any), so it follows the word before that.
        remember(chip.text, ctx.previous)
    }

    /** Adds [emoji] after the word being typed (finishing it first) or after the last word. */
    private fun pickEmoji(editor: TextEditor, emoji: String) {
        val ctx = context(editor)
        lastCorrection = null
        val swipe = swiped
        val word = when {
            swipe != null && ctx.fragment == swipe.word -> swipe.word
            ctx.fragment.isEmpty() -> null
            else -> autocorrectFor(ctx) ?: ctx.fragment
        }
        if (word != null && swipe?.word != word) {
            learn(ctx.fragment)
            remember(word, ctx.previous)
        }
        finishSwipe()
        phantomSpace = false
        editor.batch {
            if (word != null) {
                editor.deleteBefore(ctx.fragment.length)
                editor.insert("$word $emoji ")
            } else {
                val before = editor.textBeforeCursor(1)?.toString().orEmpty()
                editor.insert(if (before.isNotEmpty() && !before.last().isWhitespace()) " $emoji " else "$emoji ")
            }
        }
        lastWord = null
        autoSpaced = true
    }

    /**
     * Removes [word] from suggestions (long-press on the strip): forgets it if
     * it was learned, and stops suggesting it if it's a dictionary word.
     */
    fun onRemove(word: String) {
        learned.remove(word)
        lastResult = null
    }

    // --- Helpers ---------------------------------------------------------------------------

    private fun autocorrectFor(ctx: WordContext): String? {
        if (capitalizeI) capitalI(ctx.fragment)?.let { return it }
        if (!autocorrectEnabled || !suggestionsEnabled) return null
        if (ctx.fragment.isEmpty() || isKeptAsTyped(ctx.fragment)) return null
        val s = suggester ?: return null
        val result = lastResult?.takeIf { it.typed == ctx.fragment }
            ?: s.suggest(ctx.fragment, ctx.previous, before = ctx.before)
        return result.autocorrect?.word
    }

    private fun keepAsTyped(word: String) {
        rejected += word
        if (learningEnabled) learned.keepAsTyped(word)
    }

    private fun isKeptAsTyped(word: String) = word in rejected || learned.isKeptAsTyped(word)

    /** The swiped word is final once the user moves on: learn it. */
    private fun finishSwipe() {
        swiped?.let { remember(it.word, it.previous) }
        swiped = null
    }

    /** Records finished text ("word", or "let go" from a split) for frequency and word pairs. */
    private fun remember(text: String, previous: String?) {
        lastWord = text.substringAfterLast(' ').ifEmpty { null }
        if (!learningEnabled) return
        var prev = previous
        for (word in text.split(' ')) {
            if (word.isEmpty()) continue
            learned.recordWord(word, prev)
            prev = word
        }
    }

    private fun forgetPhrase(text: String, previous: String?) {
        var prev = previous
        for (word in text.split(' ')) {
            if (word.isEmpty()) continue
            learned.retractWord(word, prev)
            prev = word
        }
    }

    private fun learn(word: String) {
        if (!learningEnabled || word.length < 2) return
        val s = suggester ?: return
        // Count unknown words, and keep counting ones already being learned.
        if (!s.isKnown(word) || learned.has(word)) learned.recordUse(word)
    }

    companion object {
        const val CONTEXT_CHARS = 64

        private val LOWER_I = Regex("i(?:['’](?:m|ll|ve|d))?")

        /** "I", "I'm", "I'll", "I've", "I'd" for their lowercase forms; null for anything else. */
        fun capitalI(word: String): String? =
            if (LOWER_I.matches(word)) "I" + word.substring(1) else null
        private val ATTACHING_PUNCTUATION = setOf(".", ",", "!", "?", ";", ":")

        /** Characters a swiped word can follow without a space: "(word", "\"word". */
        private val OPENERS = setOf('(', '[', '{', '"', '“', '\'', '‘', '@', '#', '/', '-')
    }
}
