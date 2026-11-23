package app.weft.keyboard.predict

import app.weft.keyboard.layout.KeyGeometry
import app.weft.keyboard.layout.Layouts
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Tap-typing benchmark on the real dictionary (the swipe one is SwipeAccuracyTest).
 * Data comes from tools/bench/data (see its README); skipped if it hasn't been built.
 *
 *  - Autocorrect: Wikipedia's common misspellings. Fixed = space gives the
 *    right word; wrong = it gives another word; missed = it leaves the typo.
 *    That list is share-alike, so it isn't in the repo: CI downloads it to
 *    tools/bench/cache, and so does `python3 tools/bench/make_bench.py
 *    --misspellings`. Without it this part is skipped (and says so).
 *  - False corrections: real words from held-out sentences that space would
 *    wrongly change.
 *  - Next word: how often the next word is among the three predictions, with the
 *    next-word model when it's built (and the dictionary alone, for comparison),
 *    on the held-out sentences and on held-out conversations. Reported apart:
 *    the first word of a sentence or phrase (after . , ! ? and the like), which
 *    only the model predicts.
 *  - Keystrokes saved: letters you don't type when you tap a suggestion as
 *    soon as the word shows up in the strip.
 *
 * Results print as "Benchmark: …" lines; CI shows them on the run summary.
 */
class TypingBenchmarkTest {

    private fun find(vararg paths: String): File? = paths.map(::File).firstOrNull { it.exists() }

    private fun setUp(): Triple<Suggester, BinaryDictionary, File>? {
        val dictFile = RealDictionary.file
        val data = find("../tools/bench/data", "tools/bench/data")
        if (dictFile == null || data == null || !File(data, "sentences_en.txt").exists()) return null
        val dict = BinaryDictionary.map(dictFile)
        val suggester = Suggester(dict, KeyProximity(KeyGeometry.centers(Layouts.qwerty)), LearnedWords(null))
        return Triple(suggester, dict, data)
    }

    @Test
    fun autocorrect() {
        val (suggester, dict, data) = setUp() ?: return assumeTrue("benchmark data not built", false)
        val misspellings = find("../tools/bench/cache/misspellings_en.tsv", "tools/bench/cache/misspellings_en.tsv")
        if (misspellings == null) report("autocorrect on misspellings skipped: list not downloaded (see tools/bench/data/README.md)")
        val pairs = (misspellings?.readLines() ?: emptyList())
            .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            // Only typos that aren't real words, of words the dictionary knows.
            .filter { (typo, right) -> !suggester.isKnown(typo) && dict.findIgnoreCase(right) >= 0 }
        var fixed = 0
        var wrong = 0
        for ((typo, right) in pairs) {
            val auto = suggester.suggest(typo, null).autocorrect?.word
            when {
                auto == null -> Unit
                auto.equals(right, ignoreCase = true) -> fixed++
                else -> wrong++
            }
        }
        val missed = pairs.size - fixed - wrong
        if (misspellings != null) report(
            "autocorrect fixed %.1f%%, wrong word %.1f%%, left as typed %.1f%% (%d misspellings)".format(
                pct(fixed, pairs.size), pct(wrong, pairs.size), pct(missed, pairs.size), pairs.size,
            ),
        )

        // Words from real sentences that space would change: dictionary words, and
        // words it doesn't know (mostly names), which a bold autocorrect would mangle.
        var words = 0
        var changed = 0
        // Distinct unknown words: Tatoeba repeats a few made-up names hundreds of times.
        val unknown = HashSet<String>()
        val unknownChanged = HashSet<String>()
        for (sentence in sentences(data)) {
            for (chunk in chunks(sentence)) {
                var previous: String? = null
                for (w in chunk) {
                    val auto = suggester.suggest(w, previous).autocorrect
                    if (suggester.isKnown(w)) {
                        words++
                        if (auto != null) changed++
                    } else {
                        unknown += w
                        if (auto != null) unknownChanged += w
                    }
                    previous = w
                }
            }
        }
        report("false corrections %.2f%% of %d real words".format(pct(changed, words), words))
        report("unknown words changed %.1f%% of %d distinct".format(pct(unknownChanged.size, unknown.size), unknown.size))
        if (misspellings != null) assertTrue("autocorrect fixes too few misspellings", pct(fixed, pairs.size) >= MIN_FIXED)
        assertTrue("autocorrect changes too many real words", pct(changed, words) <= MAX_FALSE)
    }

    @Test
    fun prediction() {
        val (dictOnly, dict, data) = setUp() ?: return assumeTrue("benchmark data not built", false)
        val model = RealNextWordModel.load()
        val suggester = Suggester(dict, KeyProximity(KeyGeometry.centers(Layouts.qwerty)), LearnedWords(null), model)
        if (model == null) report("next-word model not built: predictions use the dictionary alone")
        var predicted = 0
        var dictPredicted = 0
        var targets = 0
        val starts = Tally()
        var typed = 0
        var full = 0
        for ((i, sentence) in sentences(data).withIndex()) {
            forEachWord(sentence) { p ->
                if (p.previous != null) {
                    targets++
                    if (hit(suggester.suggest("", p.previous, before = p.before, sentence = p.context), p.word)) predicted++
                    if (model != null && hit(dictOnly.suggest("", p.previous, before = p.before), p.word)) dictPredicted++
                } else if (p.phraseStart) {
                    starts.count(hit(suggester.suggest("", null, sentence = p.context, phraseStart = true), p.word))
                }
                if (i % KSR_EVERY != 0) return@forEachWord
                // Keystrokes: type letters until the word is in the strip, then tap it.
                val letters = p.word.codePointCount(0, p.word.length)
                var cost = letters + 1 // all letters, then space
                for (k in 0 until letters) {
                    val prefix = p.word.substring(0, p.word.offsetByCodePoints(0, k))
                    if (k == 0 && p.previous == null && !p.phraseStart) continue
                    val result = suggester.suggest(
                        prefix, p.previous, before = p.before, sentence = p.context, phraseStart = p.phraseStart,
                    )
                    if (hit(result, p.word)) {
                        cost = k + 1
                        break
                    }
                }
                typed += cost
                full += letters + 1
            }
        }
        val alone = if (model != null) " (dictionary alone %.1f%%)".format(pct(dictPredicted, targets)) else ""
        report("next word in top 3 %.1f%%%s (%d words)".format(pct(predicted, targets), alone, targets))
        report("first word of a sentence or phrase in top 3 ${starts.percent()}")
        report("keystrokes saved %.1f%%".format(100.0 - pct(typed, full)))

        // Conversation: Taskmaster dialogs no model trained on (tools/bench/data/README.md).
        val conversation = File(data, "conversation_en.txt").takeIf { it.exists() }
        if (conversation != null) {
            var hits = 0
            var dictHits = 0
            var n = 0
            val convStarts = Tally()
            for (sentence in conversation.readLines().filter { it.isNotBlank() }) {
                forEachWord(sentence) { p ->
                    if (p.previous == null) {
                        if (p.phraseStart) {
                            convStarts.count(hit(suggester.suggest("", null, sentence = p.context, phraseStart = true), p.word))
                        }
                        return@forEachWord
                    }
                    n++
                    if (hit(suggester.suggest("", p.previous, before = p.before, sentence = p.context), p.word)) hits++
                    if (model != null && hit(dictOnly.suggest("", p.previous, before = p.before), p.word)) dictHits++
                }
            }
            val convAlone = if (model != null) " (dictionary alone %.1f%%)".format(pct(dictHits, n)) else ""
            report("next word in conversation %.1f%%%s (%d words)".format(pct(hits, n), convAlone, n))
            report("first word of a sentence or phrase in conversation ${convStarts.percent()}")
        }
        assertTrue("next-word predictions got worse", pct(predicted, targets) >= MIN_NEXT_WORD)
        if (model != null) assertTrue("sentence-start predictions got worse", starts.pct() >= MIN_PHRASE_START)
    }

    /** Hits out of tries, for one benchmark line. */
    private class Tally {
        var hits = 0
        var n = 0

        fun count(hit: Boolean) {
            n++
            if (hit) hits++
        }

        fun pct() = if (n == 0) 0.0 else hits * 100.0 / n

        fun percent() = "%.1f%% (%d words)".format(pct(), n)
    }

    /**
     * One word of a benchmark sentence: the word before it and the one before that (within runs
     * without punctuation, like the keyboard's TextContext), the sentence so far as the next-word
     * model sees it, and whether the keyboard would treat it as starting a sentence or phrase.
     */
    private class Position(
        val word: String,
        val previous: String?,
        val before: String?,
        val context: List<String>,
        val phraseStart: Boolean,
    )

    /** Calls [action] for each word of [sentence]. */
    private fun forEachWord(sentence: String, action: (Position) -> Unit) {
        val all = WORD.findAll(sentence).toList()
        val chunks = chunks(sentence)
        val aligned = chunks.sumOf { it.size } == all.size
        var g = 0
        for (chunk in chunks) {
            for ((j, w) in chunk.withIndex()) {
                val match = if (aligned) all[g++] else null
                val context = match?.let { TextContext.sentenceWords(sentence, it.range.first) } ?: emptyList()
                val start = j == 0 && match != null && TextContext.parse(sentence.substring(0, match.range.first)).phraseStart
                action(Position(w, chunk.getOrNull(j - 1), chunk.getOrNull(j - 2), context, start))
            }
        }
    }

    private fun hit(result: SuggestionResult, word: String) =
        result.suggestions.any { it.word.equals(word, ignoreCase = true) }

    private fun sentences(data: File) = File(data, "sentences_en.txt").readLines().filter { it.isNotBlank() }

    /** Words in runs without punctuation, like the keyboard's own context (TextContext). */
    private fun chunks(sentence: String): List<List<String>> =
        sentence.split(Regex("[^\\p{L}\\p{M}'’\\s-]+|\\s-\\s"))
            .map { part -> WORD.findAll(part).map { it.value.replace('’', '\'') }.toList() }
            .filter { it.isNotEmpty() }

    private fun pct(a: Int, b: Int) = if (b == 0) 0.0 else a * 100.0 / b

    private fun report(line: String) = println("Benchmark: $line")

    private companion object {
        val WORD = Regex("\\p{L}+(?:['’]\\p{L}+)*")
        const val KSR_EVERY = 2

        // Floors, set a little below the last measured results.
        // Before the benchmark existed (Oct 2026): fixed 35.9%, next word 22.3%, keystrokes saved 39.8%.
        // Next word was 36.8% with the dictionary alone, 44.8% with the next-word model (Nov 2026).
        const val MIN_FIXED = 65.0
        const val MAX_FALSE = 0.5
        const val MIN_NEXT_WORD = 42.0
        // With the model, Nov 2026: 25.2% (the dictionary alone has nothing there).
        const val MIN_PHRASE_START = 22.0
    }
}
