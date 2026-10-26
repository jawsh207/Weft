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
}

data class SuggestionChip(val text: String, val style: ChipStyle = ChipStyle.Normal)

/**
 * Word-level typing behavior, independent of Android so it can be unit tested:
 * autocorrect when a word ends, backspace to undo an autocorrect, smart spacing
 * around punctuation after a picked suggestion, and learning new words.
 */
class TypingController(private val learned: LearnedWords) {

    /** Set once the dictionary has loaded; null until then. */
    @Volatile
    var suggester: Suggester? = null

    var suggestionsEnabled = true
    var autocorrectEnabled = true

    /** False in fields that ask for no personalized learning (e.g. incognito). */
    var learningEnabled = true

    private data class Correction(val original: String, val corrected: String, val separator: String)

    private var lastCorrection: Correction? = null
    private var autoSpaced = false

    /** The last swiped word and the decoder's other guesses for that swipe. */
    private data class Swiped(val word: String, val alternatives: List<String>)

    private var swiped: Swiped? = null

    /** After a swipe, the next tapped letter starts a new word with a space. */
    private var phantomSpace = false
    private val rejected = HashSet<String>()

    @Volatile
    private var lastResult: SuggestionResult? = null

    fun onInputStarted() {
        lastCorrection = null
        autoSpaced = false
        lastResult = null
        swiped = null
        phantomSpace = false
    }

    fun context(editor: TextEditor): WordContext =
        TextContext.parse(editor.textBeforeCursor(CONTEXT_CHARS))

    // --- Suggestions --------------------------------------------------------------------

    /** Safe to call off the main thread. */
    fun compute(ctx: WordContext): SuggestionResult {
        val s = suggester
        if (!suggestionsEnabled || s == null) return SuggestionResult.EMPTY
        return s.suggest(ctx.fragment, ctx.previous)
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

        val auto = result.autocorrect?.takeIf { autocorrectEnabled && result.typed !in rejected }
        if (auto != null) {
            val alt = words.firstOrNull { it != auto.word }?.let { SuggestionChip(it) }
            return listOf(
                SuggestionChip(result.typed, ChipStyle.Verbatim),
                SuggestionChip(auto.word, ChipStyle.Autocorrect),
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
        swiped = null
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
        val before = editor.textBeforeCursor(1)?.toString().orEmpty()
        val needsSpace = before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in OPENERS
        editor.insert(if (needsSpace) " $word" else word)
        swiped = Swiped(word, candidates)
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
            lastCorrection = Correction(ctx.fragment, fix, separator)
        } else {
            learn(ctx.fragment)
            if (separator.isNotEmpty()) editor.insert(separator)
        }
    }

    /**
     * Undoes the last autocorrect if backspace is pressed right after it.
     * Returns true if it did; otherwise the caller deletes normally.
     */
    fun onBackspace(editor: TextEditor): Boolean {
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
        rejected += c.original // don't fight the user on this word again
        learn(c.original)
        return true
    }

    /** Replaces the word being typed with [chip] and adds a space. */
    fun onPick(editor: TextEditor, chip: SuggestionChip) {
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
        swiped = null
        phantomSpace = false
        editor.batch {
            if (ctx.fragment.isNotEmpty()) editor.deleteBefore(ctx.fragment.length)
            editor.insert(chip.text + " ")
        }
        autoSpaced = true
        if (chip.style == ChipStyle.Verbatim) {
            rejected += chip.text
            if (learningEnabled) learned.recordUse(chip.text, explicit = true)
        }
    }

    // --- Helpers ---------------------------------------------------------------------------

    private fun autocorrectFor(ctx: WordContext): String? {
        if (!autocorrectEnabled || !suggestionsEnabled) return null
        if (ctx.fragment.isEmpty() || ctx.fragment in rejected) return null
        val s = suggester ?: return null
        val result = lastResult?.takeIf { it.typed == ctx.fragment } ?: s.suggest(ctx.fragment, ctx.previous)
        return result.autocorrect?.word
    }

    private fun learn(word: String) {
        if (!learningEnabled || word.length < 2) return
        val s = suggester ?: return
        // Count unknown words, and keep counting ones already being learned.
        if (!s.isKnown(word) || learned.has(word)) learned.recordUse(word)
    }

    companion object {
        const val CONTEXT_CHARS = 64
        private val ATTACHING_PUNCTUATION = setOf(".", ",", "!", "?", ";", ":")

        /** Characters a swiped word can follow without a space: "(word", "\"word". */
        private val OPENERS = setOf('(', '[', '{', '"', '“', '\'', '‘', '@', '#', '/', '-')
    }
}
