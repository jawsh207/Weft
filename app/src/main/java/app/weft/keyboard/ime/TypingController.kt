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
    private val rejected = HashSet<String>()

    @Volatile
    private var lastResult: SuggestionResult? = null

    fun onInputStarted() {
        lastCorrection = null
        autoSpaced = false
        lastResult = null
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

    fun chips(result: SuggestionResult): List<SuggestionChip?> {
        if (!suggestionsEnabled) return emptyList()
        val words = result.suggestions.map { it.word }
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
        if (!result.typedIsKnown) {
            // Offer to keep the unknown word as typed, plus the two best alternatives.
            return listOf(
                SuggestionChip(result.typed, ChipStyle.Verbatim),
                words.getOrNull(0)?.let { SuggestionChip(it) },
                words.getOrNull(1)?.let { SuggestionChip(it) },
            )
        }
        return centered(words.map { SuggestionChip(it) })
    }

    /** Best in the middle, like most keyboards: [2nd, 1st, 3rd]. */
    private fun centered(chips: List<SuggestionChip>): List<SuggestionChip?> =
        if (chips.isEmpty()) emptyList() else listOf(chips.getOrNull(1), chips[0], chips.getOrNull(2))

    // --- Editing ---------------------------------------------------------------------------

    /** Letters, digits, emoji: anything that continues or starts a word. */
    fun onText(editor: TextEditor, text: String) {
        lastCorrection = null
        autoSpaced = false
        editor.insert(text)
    }

    /**
     * Ends the current word with [separator] (" ", ",", "\n", …), autocorrecting
     * it first if appropriate. An empty [separator] just finishes the word,
     * e.g. before an editor action like Send.
     */
    fun onSeparator(editor: TextEditor, separator: String) {
        lastCorrection = null
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
    }
}
