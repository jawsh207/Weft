package app.weft.keyboard.predict

/**
 * The word being typed and the word before it, read from the text before the
 * cursor. [previous] is null at the start of a sentence or after punctuation,
 * since next-word predictions don't carry across those.
 */
data class WordContext(val fragment: String, val previous: String?)

object TextContext {

    fun parse(before: CharSequence?): WordContext {
        val text = before?.toString().orEmpty()
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

        // Previous word: only across plain spaces.
        var i = start
        while (i > 0 && text[i - 1] == ' ') i--
        if (i == start) return WordContext(fragment, null) // no space before: no previous word
        end = i
        var s = end
        while (s > 0) {
            val cp = Character.codePointBefore(text, s)
            if (!isWordChar(cp)) break
            s -= Character.charCount(cp)
        }
        while (s < end && (text[s] == '\'' || text[s] == '’' || text[s] == '-')) s++
        val previous = text.substring(s, end).takeIf { it.isNotEmpty() }
        return WordContext(fragment, previous)
    }

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
