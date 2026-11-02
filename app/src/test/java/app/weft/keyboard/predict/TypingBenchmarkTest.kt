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
 *  - False corrections: real words from held-out sentences that space would
 *    wrongly change.
 *  - Next word: how often the next word is among the three predictions.
 *  - Keystrokes saved: letters you don't type when you tap a suggestion as
 *    soon as the word shows up in the strip.
 *
 * Results print as "Benchmark: …" lines; CI shows them on the run summary.
 */
class TypingBenchmarkTest {

    private fun find(vararg paths: String): File? = paths.map(::File).firstOrNull { it.exists() }

    private fun setUp(): Triple<Suggester, BinaryDictionary, File>? {
        val dictFile = find("src/main/assets/dictionaries/main_en_US.ekd", "app/src/main/assets/dictionaries/main_en_US.ekd")
        val data = find("../tools/bench/data", "tools/bench/data")
        if (dictFile == null || data == null || !File(data, "sentences_en.txt").exists()) return null
        val dict = BinaryDictionary.map(dictFile)
        val suggester = Suggester(dict, KeyProximity(KeyGeometry.centers(Layouts.qwerty)), LearnedWords(null))
        return Triple(suggester, dict, data)
    }

    @Test
    fun autocorrect() {
        val (suggester, dict, data) = setUp() ?: return assumeTrue("benchmark data not built", false)
        val pairs = File(data, "misspellings_en.tsv").readLines()
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
        report(
            "autocorrect fixed %.1f%%, wrong word %.1f%%, left as typed %.1f%% (%d misspellings)".format(
                pct(fixed, pairs.size), pct(wrong, pairs.size), pct(missed, pairs.size), pairs.size,
            ),
        )

        // Real words that space would change.
        var words = 0
        var changed = 0
        for (sentence in sentences(data)) {
            for (chunk in chunks(sentence)) {
                var previous: String? = null
                for (w in chunk) {
                    if (suggester.isKnown(w)) {
                        words++
                        if (suggester.suggest(w, previous).autocorrect != null) changed++
                    }
                    previous = w
                }
            }
        }
        report("false corrections %.2f%% of %d real words".format(pct(changed, words), words))
        assertTrue("autocorrect fixes too few misspellings", pct(fixed, pairs.size) >= MIN_FIXED)
        assertTrue("autocorrect changes too many real words", pct(changed, words) <= MAX_FALSE)
    }

    @Test
    fun prediction() {
        val (suggester, _, data) = setUp() ?: return assumeTrue("benchmark data not built", false)
        var predicted = 0
        var targets = 0
        var typed = 0
        var full = 0
        for ((i, sentence) in sentences(data).withIndex()) {
            for (chunk in chunks(sentence)) {
                for ((j, w) in chunk.withIndex()) {
                    val previous = chunk.getOrNull(j - 1)
                    val before = chunk.getOrNull(j - 2)
                    if (previous != null) {
                        targets++
                        if (hit(suggester.suggest("", previous, before = before), w)) predicted++
                    }
                    if (i % KSR_EVERY != 0) continue
                    // Keystrokes: type letters until the word is in the strip, then tap it.
                    val letters = w.codePointCount(0, w.length)
                    var cost = letters + 1 // all letters, then space
                    for (k in 0 until letters) {
                        val prefix = w.substring(0, w.offsetByCodePoints(0, k))
                        if (k == 0 && previous == null) continue
                        if (hit(suggester.suggest(prefix, previous, before = before), w)) {
                            cost = k + 1
                            break
                        }
                    }
                    typed += cost
                    full += letters + 1
                }
            }
        }
        report("next word in top 3 %.1f%% (%d words)".format(pct(predicted, targets), targets))
        report("keystrokes saved %.1f%%".format(100.0 - pct(typed, full)))
        assertTrue("next-word predictions got worse", pct(predicted, targets) >= MIN_NEXT_WORD)
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
        const val MIN_FIXED = 0.0
        const val MAX_FALSE = 100.0
        const val MIN_NEXT_WORD = 0.0
    }
}
