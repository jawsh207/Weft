package app.weft.keyboard.predict

/**
 * The word being typed and the word before it, read from the text before the
 * cursor. [previous] is null at the start of a sentence or after punctuation,
 * since next-word predictions don't carry across those.
 */
data class WordContext(
    val fragment: String,
    val previous: String?,
    /** The word before [previous], under the same rules (plain spaces only). */
    val before: String? = null,
    /**
     * Every word of the current sentence before [fragment], across commas and the like but not
     * across . ! ? or a new line: what the next-word model reads ([NextWordModel]).
     */
    val sentence: List<String> = emptyList(),
    /**
     * Nothing is being typed and the cursor starts a sentence or phrase: the field is empty, or
     * the text before ends in a line break, or in . ! ? … , ; : and a space. The next-word model
     * can predict here even though there is no [previous] word.
     */
    val phraseStart: Boolean = false,
)

/**
 * Works out the word being typed and the words before it from the raw text before the cursor, and
 * says which characters belong to words.
 */
object TextContext {

    /**
     * Splits [before] (the text before the cursor) into the word being typed and the one or two
     * words before it.
     */
    fun parse(before: CharSequence?): WordContext {
        val text = before?.toString().orEmpty()
        val ctx = parseWords(text)
        return if (before != null && ctx.fragment.isEmpty() && ctx.previous == null && opensPhrase(text)) {
            ctx.copy(phraseStart = true)
        } else {
            ctx
        }
    }

    /**
     * Whether [text] (the text before the cursor) ends where a sentence or phrase begins: it is
     * empty, ends in a line break, or ends in sentence or clause punctuation followed by a space.
     */
    fun opensPhrase(text: String): Boolean {
        var i = text.length
        while (i > 0 && text[i - 1] == ' ') i--
        if (i == 0 || text[i - 1] == '\n') return true
        return i < text.length && text[i - 1] in PHRASE_ENDS
    }

    private const val PHRASE_ENDS = ".!?…,;:"

    private fun parseWords(text: String): WordContext {
        var end = text.length
        var start = end
        while (start > 0) {
            val cp = Character.codePointBefore(text, start)
            if (!isWordChar(cp)) break
            start -= Character.charCount(cp)
        }
        // A word can't start with an apostrophe or hyphen ('quoted' text).
        while (start < end && (text[start] == '\'' || text[start] == '’' || text[start] == '-')) start++
        val fragment = text.substring(start, end)
        val sentence = sentenceWords(text, start)

        // Previous word: only across plain spaces.
        var i = start
        while (i > 0 && text[i - 1] == ' ') i--
        if (i == start) return WordContext(fragment, null, sentence = sentence) // no space before: no previous word
        end = i
        var s = end
        while (s > 0) {
            val cp = Character.codePointBefore(text, s)
            if (!isWordChar(cp)) break
            s -= Character.charCount(cp)
        }
        while (s < end && (text[s] == '\'' || text[s] == '’' || text[s] == '-')) s++
        val previous = text.substring(s, end).takeIf { it.isNotEmpty() }
            ?: return WordContext(fragment, null, sentence = sentence)
        return WordContext(fragment, previous, wordBefore(text, s), sentence)
    }

    /**
     * The words of the sentence that [text] is in, up to [end]: from just after the last . ! ? or
     * line break. Digits and symbols are skipped, as the next-word model was trained.
     */
    fun sentenceWords(text: String, end: Int): List<String> {
        var from = end
        while (from > 0 && text[from - 1] !in SENTENCE_ENDS) from--
        return WORD.findAll(text.substring(from, end)).map { it.value }.toList()
    }

    private const val SENTENCE_ENDS = ".!?\n…"

    /** A word as the next-word model splits text (tools/nextword/common.py): letters, inner apostrophes. */
    private val WORD = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*")

    /** The word ending just before [start] across plain spaces, or null. */
    private fun wordBefore(text: String, start: Int): String? {
        var i = start
        while (i > 0 && text[i - 1] == ' ') i--
        if (i == start) return null
        val end = i
        var s = end
        while (s > 0) {
            val cp = Character.codePointBefore(text, s)
            if (!isWordChar(cp)) break
            s -= Character.charCount(cp)
        }
        while (s < end && (text[s] == '\'' || text[s] == '’' || text[s] == '-')) s++
        return text.substring(s, end).takeIf { it.isNotEmpty() }
    }

    /**
     * Letters, accents, apostrophes and hyphens are part of a word ("don't", "well-known"); digits
     * and everything else aren't.
     */
    fun isWordChar(cp: Int): Boolean =
        Character.isLetter(cp) ||
            Character.getType(cp).let {
                it == Character.NON_SPACING_MARK.toInt() ||
                    it == Character.COMBINING_SPACING_MARK.toInt() ||
                    it == Character.ENCLOSING_MARK.toInt()
            } ||
            cp == '\''.code || cp == '’'.code || cp == '-'.code

    /** Characters that end a word and trigger autocorrect. */
    fun isSeparator(text: String): Boolean =
        text.isNotEmpty() && text.codePoints().allMatch { !isWordChar(it) && !Character.isDigit(it) }
}
