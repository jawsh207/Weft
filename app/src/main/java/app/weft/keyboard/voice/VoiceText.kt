package app.weft.keyboard.voice

/**
 * Tidies what a speech engine hands back before it goes into the text field,
 * the same way for any engine:
 *
 *  - **Punctuation only when spoken.** Engines like Moonshine end every phrase
 *    with their own "." / "?" / "!"; those are removed. Saying "period",
 *    "question mark", "comma", … types the mark instead. The word stays a word
 *    after "the", "a", "my", … ("the period of time"), and saying it twice
 *    types it once as a word ("period period" → "period").
 *  - **Capitals that fit where the cursor is.** At the start of a sentence the
 *    first word is capitalized; in the middle of one it's lowercased, unless it's
 *    a word that's always capitalized ("I", names, places, days). A new phrase
 *    the engine capitalized only continues the sentence, so it's lowercased too,
 *    unless you said a sentence-ending mark before it.
 *
 * Pure Kotlin so it can be unit tested; [keepsCapital] answers "is this word
 * always capitalized?" from the dictionary.
 */
object VoiceText {

    private data class Mark(val text: String, val endsSentence: Boolean = false, val lineBreak: Boolean = false)

    /** Spoken punctuation, longest phrases first. */
    private val SPOKEN: List<Pair<List<String>, Mark>> = listOf(
        listOf("exclamation", "point") to Mark("!", endsSentence = true),
        listOf("exclamation", "mark") to Mark("!", endsSentence = true),
        listOf("question", "mark") to Mark("?", endsSentence = true),
        listOf("full", "stop") to Mark(".", endsSentence = true),
        listOf("new", "line") to Mark("\n", endsSentence = true, lineBreak = true),
        listOf("period") to Mark(".", endsSentence = true),
        listOf("comma") to Mark(","),
        listOf("colon") to Mark(":"),
        listOf("semicolon") to Mark(";"),
    )

    /** Words after which "period", "comma", … mean the word itself. */
    private val DETERMINERS = setOf(
        "the", "a", "an", "this", "that", "these", "those", "my", "your", "his", "her", "its", "our",
        "their", "each", "every", "one", "no", "any", "some", "another", "same", "first",
        "last", "next", "trial", "grace", "waiting",
    )

    /** Abbreviations whose dot belongs to the word, not the engine's sentence end. */
    private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "etc", "prof", "mt")

    private val SENTENCE_END = Regex("[.?!…]+$")
    private val EDGE_PUNCTUATION = Regex("^[\\p{P}&&[^'’\\-]]+|[\\p{P}&&[^'’\\-]]+$")

    /**
     * @param raw what the engine recognized so far (partial or final).
     * @param before the text before the cursor, to tell the start of a sentence from the middle.
     */
    fun format(raw: String, before: CharSequence?, keepsCapital: (String) -> Boolean): String {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""

        // 1. Drop the engine's own sentence ends: "tomorrow." at the end of a phrase,
        //    i.e. before the next capitalized phrase or at the very end.
        val words = tokens.mapIndexed { i, t ->
            val next = tokens.getOrNull(i + 1)
            val phraseEnd = next == null || next.first().isUpperCase()
            val bare = t.trimEnd('.', '?', '!', '…')
            if (phraseEnd && SENTENCE_END.containsMatchIn(t) && bare.isNotEmpty() && bare.lowercase() !in ABBREVIATIONS) bare else t
        }

        // 2. Spoken punctuation and assembly.
        val out = StringBuilder()
        var capitalizeNext = startsSentence(before)
        var firstWord = true
        var i = 0
        while (i < words.size) {
            val match = spokenAt(words, i)
            if (match != null) {
                val (length, mark) = match
                val previous = words.getOrNull(i - 1)?.let(::bareLower)
                val repeated = spokenAt(words, i + length)?.second == mark
                when {
                    previous != null && previous in DETERMINERS && out.isNotEmpty() -> Unit // "the period": a word
                    repeated -> { // "period period": the word itself, once
                        appendWord(out, words.subList(i, i + length).joinToString(" ") { bareLower(it) })
                        firstWord = false
                        capitalizeNext = false
                        i += length * 2
                        continue
                    }
                    else -> {
                        // Marks attach to the word before; a line break replaces the space.
                        while (out.endsWith(" ")) out.setLength(out.length - 1)
                        out.append(mark.text)
                        if (mark.endsSentence) capitalizeNext = true
                        i += length
                        continue
                    }
                }
            }
            var word = words[i]
            word = when {
                capitalizeNext -> word.replaceFirstChar { it.titlecase() }
                // Mid-sentence: lowercase the engine's phrase-start capital unless the word keeps it.
                word.first().isUpperCase() && (firstWord || enginePhraseStart(tokens, i)) && !keeps(word, keepsCapital) ->
                    word.replaceFirstChar { it.lowercase() }
                else -> word
            }
            appendWord(out, word)
            firstWord = false
            capitalizeNext = false
            i++
        }
        return out.toString()
    }

    /** Whether a voice result starting with [text] attaches without a space ("comma", "new line"). */
    fun attachesToPrevious(text: String): Boolean = text.firstOrNull()?.let { it in ".,?!:;\n" } == true

    private fun appendWord(out: StringBuilder, word: String) {
        if (out.isNotEmpty() && !out.endsWith(" ") && !out.endsWith("\n")) out.append(' ')
        out.append(word)
    }

    /** The engine ended the word before with its own sentence mark, so it capitalized this one. */
    private fun enginePhraseStart(tokens: List<String>, i: Int): Boolean =
        tokens.getOrNull(i - 1)?.let { SENTENCE_END.containsMatchIn(it) && it.trimEnd('.', '?', '!', '…').lowercase() !in ABBREVIATIONS } == true

    private fun keeps(word: String, keepsCapital: (String) -> Boolean): Boolean {
        val bare = word.replace(EDGE_PUNCTUATION, "")
        if (bare.isEmpty()) return true
        if (bare == "I" || bare.startsWith("I'") || bare.startsWith("I’")) return true
        if (bare.length >= 2 && bare.none { it.isLowerCase() }) return true // NASA, OK
        return keepsCapital(bare)
    }

    private fun spokenAt(words: List<String>, i: Int): Pair<Int, Mark>? {
        for ((phrase, mark) in SPOKEN) {
            if (i + phrase.size > words.size) continue
            if (phrase.indices.all { bareLower(words[i + it]) == phrase[it] }) return phrase.size to mark
        }
        return null
    }

    private fun bareLower(w: String) = w.replace(EDGE_PUNCTUATION, "").lowercase()

    /** Start of the field, or after a sentence-ending mark or a line break. */
    fun startsSentence(before: CharSequence?): Boolean {
        val text = before?.toString().orEmpty()
        val trimmed = text.trimEnd(' ', '\t')
        if (trimmed.isEmpty()) return true
        if (trimmed.last() == '\n') return true
        val last = trimmed.trimEnd('"', '”', '\'', '’', ')')
        return last.isEmpty() || last.last() in ".?!…"
    }
}
