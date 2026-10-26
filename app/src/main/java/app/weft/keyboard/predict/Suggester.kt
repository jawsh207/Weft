package app.weft.keyboard.predict

import java.util.PriorityQueue
import kotlin.math.min

enum class SuggestionKind {
    /** Matches what was typed, ignoring case/accents ("monday" → "Monday"). */
    Exact,

    /** A likely intended word ("teh" → "the"). */
    Correction,

    /** A longer word starting with what was typed ("keyb" → "keyboard"). */
    Completion,

    /** Next-word prediction when nothing is typed yet. */
    Prediction,
}

data class Suggestion(
    val word: String,
    val kind: SuggestionKind,
    val score: Float,
    /** Edit cost between the typed text and this word (0 = exact). */
    val cost: Float,
)

data class SuggestionResult(
    val typed: String,
    /** Best first; never contains the typed text itself. */
    val suggestions: List<Suggestion>,
    /** Whether the typed text is a real word (dictionary or learned). */
    val typedIsKnown: Boolean,
    /** What the space bar would replace the typed text with, if anything. */
    val autocorrect: Suggestion?,
) {
    companion object {
        val EMPTY = SuggestionResult("", emptyList(), true, null)
    }
}

/**
 * Proximity-aware fuzzy lookup over the trie: a Damerau-Levenshtein row is
 * carried down each branch and branches are pruned as soon as every cell
 * exceeds the edit budget. Ranking blends word frequency, edit cost and
 * next-word (bigram) context.
 */
class Suggester(
    private val dictionary: BinaryDictionary?,
    private val proximity: KeyProximity,
    private val learned: LearnedWords,
) {

    fun suggest(typed: String, previous: String?, limit: Int = 3): SuggestionResult {
        val dict = dictionary
        val prevNode = if (dict != null && previous != null) dict.findIgnoreCase(previous) else -1

        if (typed.isEmpty()) {
            if (dict == null || prevNode < 0) return SuggestionResult.EMPTY
            val predictions = dict.bigrams(prevNode)
                .filter { !dict.isOffensive(it.first) }
                .sortedByDescending { it.second }
                .take(limit)
                .map { (node, strength) ->
                    Suggestion(dict.word(node), SuggestionKind.Prediction, strength / 25.5f, 0f)
                }
            return SuggestionResult("", predictions, true, null)
        }

        val t = typed.lowercase().codePoints().toArray()
        val budget = budgetFor(t.size)
        val found = HashMap<String, Suggestion>()

        fun offer(s: Suggestion) {
            val existing = found[s.word]
            if (existing == null || s.score > existing.score) found[s.word] = s
        }

        if (dict != null) {
            val search = Search(dict, t, budget)
            search.run()
            for ((node, cost) in search.terminals) {
                if (dict.isOffensive(node) && cost > 0f) continue
                val word = dict.word(node)
                val kind = if (cost <= EXACT_COST) SuggestionKind.Exact else SuggestionKind.Correction
                offer(Suggestion(word, kind, score(dict.freq(node), cost, kind, dict, prevNode, node), cost))
            }
            for ((node, cost) in completions(dict, search.prefixHits)) {
                if (dict.isOffensive(node)) continue
                val word = dict.word(node)
                val extra = (word.codePointCount(0, word.length) - t.size).coerceAtLeast(1)
                val s = score(dict.freq(node), cost, SuggestionKind.Completion, dict, prevNode, node) -
                    0.12f * extra
                offer(Suggestion(word, SuggestionKind.Completion, s, cost))
            }
        }

        for ((word, freq) in learned.candidates()) {
            val (full, prefix) = distance(t, word)
            if (full <= budget) {
                val kind = if (full <= EXACT_COST) SuggestionKind.Exact else SuggestionKind.Correction
                offer(Suggestion(word, kind, score(freq, full, kind, null, -1, -1), full))
            } else if (prefix <= budget && t.size >= 2) {
                offer(Suggestion(word, SuggestionKind.Completion,
                    score(freq, prefix, SuggestionKind.Completion, null, -1, -1), prefix))
            }
        }

        val typedIsKnown = isKnown(typed)
        val ranked = found.values
            .map { it.copy(word = matchCase(it.word, typed)) }
            .filter { it.word != typed }
            .distinctBy { it.word }
            .sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.word })

        return SuggestionResult(
            typed = typed,
            suggestions = ranked.take(limit),
            typedIsKnown = typedIsKnown,
            autocorrect = if (typedIsKnown) null else pickAutocorrect(t.size, ranked),
        )
    }

    /** Is [word] a real word as typed (allowing sentence-start capitals or all caps)? */
    fun isKnown(word: String): Boolean {
        val dict = dictionary
        if (dict != null) {
            if (dict.find(word) >= 0) return true
            val lower = word.lowercase()
            val capitalizedOrCaps = word == lower.replaceFirstChar { it.titlecase() } || word == word.uppercase()
            if (lower != word && capitalizedOrCaps && dict.find(lower) >= 0) return true
        }
        return learned.isKnown(word)
    }

    // --- Scoring -----------------------------------------------------------------------------

    private fun score(
        freq: Int,
        cost: Float,
        kind: SuggestionKind,
        dict: BinaryDictionary?,
        prevNode: Int,
        node: Int,
    ): Float {
        var s = freq / 25.5f - cost * COST_WEIGHT
        if (kind == SuggestionKind.Exact) s += EXACT_BONUS
        if (kind == SuggestionKind.Completion) s -= COMPLETION_PENALTY
        if (dict != null && prevNode >= 0 && node >= 0) {
            s += dict.bigramStrength(prevNode, node) / 255f * BIGRAM_WEIGHT
        }
        return s
    }

    private fun pickAutocorrect(length: Int, ranked: List<Suggestion>): Suggestion? {
        val best = ranked.firstOrNull() ?: return null
        if (best.kind == SuggestionKind.Completion || best.kind == SuggestionKind.Prediction) return null
        val maxCost = when {
            length <= 2 -> 0.5f
            length <= 4 -> 1.0f
            else -> 1.6f
        }
        if (best.cost > maxCost) return null
        if (best.score < MIN_AUTOCORRECT_SCORE) return null
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && runnerUp.kind != SuggestionKind.Completion &&
            best.score - runnerUp.score < AUTOCORRECT_MARGIN && best.cost >= runnerUp.cost
        ) {
            return null // too close to call
        }
        return best
    }

    // --- Trie search -------------------------------------------------------------------------

    private inner class Search(val dict: BinaryDictionary, val t: IntArray, val budget: Float) {
        val n = t.size
        val terminals = HashMap<Int, Float>()
        val prefixHits = HashMap<Int, Float>()

        fun run() {
            val row0 = FloatArray(n + 1) { it * EXTRA_LETTER }
            visit(0, row0, null, -1)
        }

        private fun visit(node: Int, prev: FloatArray, prevPrev: FloatArray?, prevCh: Int) {
            val first = dict.firstChild(node)
            val count = dict.childCount(node)
            for (child in first until first + count) {
                val raw = dict.char(child)
                val ch = Character.toLowerCase(raw)
                val omit = proximity.omission(ch)
                val row = FloatArray(n + 1)
                row[0] = prev[0] + omit
                var rowMin = row[0]
                for (j in 1..n) {
                    var c = prev[j - 1] + proximity.substitution(t[j - 1], ch)
                    c = min(c, prev[j] + omit)
                    val extra = if (j >= 2 && t[j - 1] == t[j - 2]) DOUBLED_LETTER else EXTRA_LETTER
                    c = min(c, row[j - 1] + extra)
                    if (prevPrev != null && j >= 2 && t[j - 1] == prevCh && t[j - 2] == ch) {
                        c = min(c, prevPrev[j - 2] + TRANSPOSITION)
                    }
                    row[j] = c
                    if (c < rowMin) rowMin = c
                }
                if (rowMin > budget) continue
                if (row[n] <= budget) {
                    if (dict.freq(child) > 0) keepMin(terminals, child, row[n])
                    keepMin(prefixHits, child, row[n])
                }
                visit(child, row, prev, ch)
            }
        }
    }

    /** Best-first walk under each matched prefix, highest frequency first. */
    private fun completions(dict: BinaryDictionary, hits: Map<Int, Float>): Map<Int, Float> {
        val out = HashMap<Int, Float>()
        val topHits = hits.entries
            .sortedByDescending { dict.maxSub(it.key) / 25.5f - it.value * COST_WEIGHT }
            .take(MAX_PREFIX_HITS)
        for ((hit, cost) in topHits) {
            // Entries: (priority, node, isEmit). Expand by subtree max; emit by own freq.
            val queue = PriorityQueue<LongArray>(compareByDescending { it[0] })
            val firstHit = dict.firstChild(hit)
            for (c in firstHit until firstHit + dict.childCount(hit)) {
                queue.add(longArrayOf(dict.maxSub(c).toLong(), c.toLong(), 0))
            }
            var emitted = 0
            var steps = 0
            while (queue.isNotEmpty() && emitted < COMPLETIONS_PER_PREFIX && steps < MAX_COMPLETION_STEPS) {
                steps++
                val (_, nodeL, emit) = queue.poll()
                val node = nodeL.toInt()
                if (emit == 1L) {
                    keepMin(out, node, cost)
                    emitted++
                    continue
                }
                if (dict.freq(node) > 0) queue.add(longArrayOf(dict.freq(node).toLong(), nodeL, 1))
                val first = dict.firstChild(node)
                for (c in first until first + dict.childCount(node)) {
                    queue.add(longArrayOf(dict.maxSub(c).toLong(), c.toLong(), 0))
                }
            }
        }
        return out
    }

    /** (full-word cost, best prefix cost) between typed code points and [word]. */
    private fun distance(t: IntArray, word: String): Pair<Float, Float> {
        val n = t.size
        var prev = FloatArray(n + 1) { it * EXTRA_LETTER }
        var bestPrefix = prev[n]
        var i = 0
        while (i < word.length) {
            val ch = Character.toLowerCase(word.codePointAt(i))
            val omit = proximity.omission(ch)
            val row = FloatArray(n + 1)
            row[0] = prev[0] + omit
            for (j in 1..n) {
                row[j] = min(
                    min(prev[j - 1] + proximity.substitution(t[j - 1], ch), prev[j] + omit),
                    row[j - 1] + EXTRA_LETTER,
                )
            }
            prev = row
            bestPrefix = min(bestPrefix, row[n])
            i += Character.charCount(word.codePointAt(i))
        }
        return prev[n] to bestPrefix
    }

    private fun keepMin(map: HashMap<Int, Float>, key: Int, value: Float) {
        val old = map[key]
        if (old == null || value < old) map[key] = value
    }

    private fun budgetFor(length: Int): Float = when {
        length <= 1 -> 0.5f
        length <= 3 -> 1.0f
        length <= 6 -> 1.6f
        else -> 2.2f
    }

    companion object {
        /** Costs at or below this count as the same word (case/accent differences). */
        const val EXACT_COST = 0.3f
        const val EXTRA_LETTER = 1f
        const val DOUBLED_LETTER = 0.5f
        const val TRANSPOSITION = 0.6f
        const val COST_WEIGHT = 4.5f
        const val EXACT_BONUS = 2f
        const val COMPLETION_PENALTY = 1.2f
        const val BIGRAM_WEIGHT = 3f
        const val MIN_AUTOCORRECT_SCORE = 1.5f
        const val AUTOCORRECT_MARGIN = 0.6f
        const val MAX_PREFIX_HITS = 12
        const val COMPLETIONS_PER_PREFIX = 4
        const val MAX_COMPLETION_STEPS = 400

        /** Applies the user's capitalization to a suggestion. */
        fun matchCase(word: String, typed: String): String {
            if (typed.isEmpty()) return word
            val typedLetters = typed.filter { it.isLetter() }
            return when {
                typedLetters.length >= 2 && typedLetters.all { it.isUpperCase() } -> word.uppercase()
                typed[0].isUpperCase() && word[0].isLowerCase() -> word.replaceFirstChar { it.titlecase() }
                else -> word
            }
        }
    }
}
