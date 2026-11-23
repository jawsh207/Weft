package app.weft.keyboard.predict

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * For training the next-word model (tools/nextword, run by its workflow): writes, for every
 * next-word position the typing benchmark scores, the sentence so far and the dictionary's top 30
 * candidates with their scores, so the model can be tuned and measured on exactly the lists
 * Suggester will hand it, plus the dictionary's word list ("<file>.words.tsv"). The first word of
 * each sentence or phrase is written too, with no candidates and "start": true.
 *
 * Runs only when WEFT_NEXT_WORD_DUMP names the output file. WEFT_NEXT_WORD_SENTENCES picks the
 * sentences file in tools/bench/data (sentences_en.txt by default).
 */
class NextWordDumpTest {
    @Test
    fun dump() {
        val out = System.getenv("WEFT_NEXT_WORD_DUMP")
        assumeTrue("set WEFT_NEXT_WORD_DUMP to run", out != null)
        val dictFile = RealDictionary.file
        assumeTrue("dictionary not built", dictFile != null)
        val data = listOf("../tools/bench/data", "tools/bench/data").map(::File).first { it.isDirectory }
        val suggester = Suggester(
            BinaryDictionary.map(dictFile!!),
            KeyProximity(app.weft.keyboard.layout.KeyGeometry.centers(app.weft.keyboard.layout.Layouts.qwerty)),
            LearnedWords(null),
        )
        var rows = 0
        File(out!!).bufferedWriter().use { w ->
            val name = System.getenv("WEFT_NEXT_WORD_SENTENCES") ?: "sentences_en.txt"
            for ((index, sentence) in File(data, name).readLines().filter { it.isNotBlank() }.withIndex()) {
                val all = WORD.findAll(sentence).toList()
                val chunks = sentence.split(Regex("[^\\p{L}\\p{M}'’\\s-]+|\\s-\\s"))
                    .map { part -> WORD.findAll(part).map { it.value }.toList() }
                    .filter { it.isNotEmpty() }
                if (chunks.sumOf { it.size } != all.size) continue // can't line words up with the text
                var g = 0
                for (chunk in chunks) {
                    for ((j, raw) in chunk.withIndex()) {
                        val match = all[g++]
                        val prefix = sentence.substring(0, match.range.first)
                        val previous = chunk.getOrNull(j - 1)?.replace('’', '\'')
                        if (previous == null) {
                            // The first word of a sentence or phrase: only the model predicts these.
                            if (j == 0 && TextContext.parse(prefix).phraseStart) {
                                w.write("{\"i\":$index,\"prefix\":${json(prefix)},\"target\":" +
                                    "${json(raw.replace('’', '\''))},\"cands\":[],\"start\":true}\n")
                                rows++
                            }
                            continue
                        }
                        val before = chunk.getOrNull(j - 2)?.replace('’', '\'')
                        val result = suggester.suggest("", previous, limit = Suggester.MODEL_CANDIDATES, before = before)
                        val cands = result.suggestions.joinToString(",") { "[${json(it.word)},${it.score}]" }
                        w.write("{\"i\":$index,\"prefix\":${json(prefix)}," +
                            "\"target\":${json(raw.replace('’', '\''))},\"cands\":[$cands]}\n")
                        rows++
                    }
                }
            }
        }
        // The dictionary's suggestible words, so models adding their own guesses stay within it.
        val dict = BinaryDictionary.map(dictFile)
        File("$out.words.tsv").bufferedWriter().use { w ->
            for (node in 1 until dict.nodeCount) {
                if (dict.freq(node) > 0 && !dict.isOffensive(node)) w.write("${dict.word(node)}\t${dict.freq(node)}\n")
            }
        }
        println("Next-word dump: $rows positions written to $out")
    }

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        val WORD = Regex("\\p{L}+(?:['’]\\p{L}+)*")
    }
}
