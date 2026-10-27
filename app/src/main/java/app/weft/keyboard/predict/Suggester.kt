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
            if (previous == null) return SuggestionResult.EMPTY
            return SuggestionResult("", predict(dict, prevNode, previous, limit), true, null)
        }

        val t = typed.lowercase().codePoints().toArray()
        val budget = budgetFor(t.size)
        val found = HashMap<String, Suggestion>()

        fun offer(s: Suggestion) {
            if (learned.isRemoved(s.word)) return
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
                offer(Suggestion(word, kind, score(word, dict.freq(node), cost, kind, dict, prevNode, node, previous), cost))
            }
            for ((node, cost) in completions(dict, search.prefixHits)) {
                if (dict.isOffensive(node)) continue
                val word = dict.word(node)
                val extra = (word.codePointCount(0, word.length) - t.size).coerceAtLeast(1)
                val s = score(word, dict.freq(node), cost, SuggestionKind.Completion, dict, prevNode, node, previous) -
                    0.12f * extra
                offer(Suggestion(word, SuggestionKind.Completion, s, cost))
            }
        }

        for ((word, freq) in learned.candidates()) {
            val (full, prefix) = distance(t, word)
            if (full <= budget) {
                val kind = if (full <= EXACT_COST) SuggestionKind.Exact else SuggestionKind.Correction
                offer(Suggestion(word, kind, score(word, freq, full, kind, null, -1, -1, previous), full))
            } else if (prefix <= budget && t.size >= 2) {
                offer(Suggestion(word, SuggestionKind.Completion,
                    score(word, freq, prefix, SuggestionKind.Completion, null, -1, -1, previous), prefix))
            }
        }

        val typedIsKnown = isKnown(typed)
        if (dict != null && !typedIsKnown) splits(dict, typed, prevNode).forEach(::offer)

        val ranked = found.values
            .map { it.copy(word = matchCase(it.word, typed)) }
            .filter { it.word != typed }
            .distinctBy { it.word }
            .sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.word })

        return SuggestionResult(
            typed = typed,
            suggestions = ranked.take(limit),
            typedIsKnown = typedIsKnown,
            autocorrect = if (typedIsKnown) contractionFor(typed, ranked) else pickAutocorrect(t.size, ranked),
        )
    }

    /**
     * Real words that are almost always a contraction typed without the
     * apostrophe ("cant" → "can't", "wont" → "won't"). Only fires when the
     * contraction is far more common, so "its", "well", "were", "ill" stay put.
     */
    private fun contractionFor(typed: String, ranked: List<Suggestion>): Suggestion? {
        val dict = dictionary ?: return null
        val typedNode = dict.find(typed).takeIf { it >= 0 } ?: dict.find(typed.lowercase())
        if (typedNode < 0) return null
        val candidate = ranked.firstOrNull {
            it.kind == SuggestionKind.Exact && '\'' in it.word &&
                it.word.replace("'", "").equals(typed, ignoreCase = true)
        } ?: return null
        val candidateNode = dict.findIgnoreCase(candidate.word)
        if (candidateNode < 0) return null
        return candidate.takeIf { dict.freq(candidateNode) - dict.freq(typedNode) >= CONTRACTION_MARGIN }
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

    // --- Next-word predictions ---------------------------------------------------------------

    /**
     * What usually comes after [previous]: the user's own word pairs first
     * (they're the strongest signal), then the dictionary's, each nudged by
     * how often the user types that word.
     */
    private fun predict(dict: BinaryDictionary?, prevNode: Int, previous: String, limit: Int): List<Suggestion> {
        val found = HashMap<String, Suggestion>()
        fun offer(word: String, score: Float) {
            if (learned.isRemoved(word)) return
            val key = word.lowercase()
            val old = found[key]
            if (old == null || score > old.score) found[key] = Suggestion(word, SuggestionKind.Prediction, score, 0f)
        }
        if (dict != null && prevNode >= 0) {
            for ((node, strength) in dict.bigrams(prevNode)) {
                if (dict.isOffensive(node)) continue
                val word = dict.word(node)
                offer(word, strength / 25.5f + learned.boost(word) + learned.pairBoost(previous, word))
            }
        }
        for ((word, uses) in learned.nextWords(previous)) {
            // Prefer the dictionary's spelling ("i" → "I", "monday" → "Monday").
            val display = dict?.findIgnoreCase(word)?.takeIf { it >= 0 }?.let { dict.word(it) }
                ?: learned.candidates().firstOrNull { it.first.equals(word, ignoreCase = true) }?.first
                ?: word
            if (dict != null && dict.findIgnoreCase(word).let { it >= 0 && dict.isOffensive(it) }) continue
            offer(display, PERSONAL_PREDICTION_BASE + learned.pairBoost(previous, word) + learned.boost(word) + uses * 0.01f)
        }
        return found.values.sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.word }).take(limit)
    }

    // --- Scoring -----------------------------------------------------------------------------

    private fun score(
        word: String,
        freq: Int,
        cost: Float,
        kind: SuggestionKind,
        dict: BinaryDictionary?,
        prevNode: Int,
        node: Int,
        previous: String?,
    ): Float {
        var s = freq / 25.5f - cost * COST_WEIGHT
        if (kind == SuggestionKind.Exact) s += EXACT_BONUS
        if (kind == SuggestionKind.Completion) s -= COMPLETION_PENALTY
        if (dict != null && prevNode >= 0 && node >= 0) {
            s += dict.bigramStrength(prevNode, node) / 255f * BIGRAM_WEIGHT
        }
        // The user's own habits: words they use often, and what they type after [previous].
        s += learned.boost(word) + learned.pairBoost(previous, word)
        return s
    }

    /**
     * What space should insert for an unknown word: the best suggestion, which is
     * also what the strip shows in the middle. Completions count too, so
     * "keyb" + space gives "keyboard". Returns null (keep as typed) when nothing
     * is a confident match.
     */
    private fun pickAutocorrect(length: Int, ranked: List<Suggestion>): Suggestion? {
        // An unknown word that differs from a real one only by case, accents or an
        // apostrophe ("im" → "I'm", "monday" → "Monday") is almost certainly that word.
        // But not a rare one ("th" shouldn't become the abbreviation "Th" when "the" is right there).
        val best = ranked.firstOrNull() ?: return null
        ranked.firstOrNull { it.kind == SuggestionKind.Exact }
            ?.takeIf { it.score >= best.score - EXACT_PRIORITY }
            ?.let { return it }
        if (best.kind == SuggestionKind.Prediction) return null
        val maxCost = when {
            length <= 2 -> 0.5f
            length <= 4 -> 1.0f
            else -> 1.6f
        }
        if (best.cost > maxCost) return null
        val minScore = if (best.kind == SuggestionKind.Completion) MIN_COMPLETION_SCORE else MIN_AUTOCORRECT_SCORE
        if (best.score < minScore) return null
        return best
    }

    /**
     * Two or three words typed without spaces ("letgo" → "let go",
     * "iloveyou" → "I love you"). Every part must be a reasonably common word,
     * so rare fragments don't produce nonsense splits.
     */
    private fun splits(dict: BinaryDictionary, typed: String, prevNode: Int): List<Suggestion> {
        val cps = typed.codePoints().toArray()
        if (cps.size < 3 || typed.any { !it.isLetter() && it != '\'' }) return emptyList()

        fun part(from: Int, to: Int): Int {
            val text = String(cps, from, to - from)
            val single = to - from == 1
            if (single && text.lowercase() != "a" && text.lowercase() != "i") return -1
            val node = dict.findIgnoreCase(text)
            return if (node < 0 || dict.isOffensive(node) || dict.freq(node) < MIN_SPLIT_FREQ) -1 else node
        }

        fun suggestion(nodes: List<Int>): Suggestion {
            var score = nodes.minOf { dict.freq(it) } / 25.5f - SPLIT_COST * (nodes.size - 1) * COST_WEIGHT
            for (i in 1 until nodes.size) score += dict.bigramStrength(nodes[i - 1], nodes[i]) / 255f * BIGRAM_WEIGHT
            if (prevNode >= 0) score += dict.bigramStrength(prevNode, nodes[0]) / 255f * BIGRAM_WEIGHT
            val text = nodes.joinToString(" ") { dict.word(it) }
            return Suggestion(text, SuggestionKind.Correction, score, SPLIT_COST * (nodes.size - 1))
        }

        val n = cps.size
        val out = ArrayList<Suggestion>()
        for (i in 1 until n) {
            val a = part(0, i)
            if (a < 0) continue
            val b = part(i, n)
            if (b >= 0) out += suggestion(listOf(a, b))
            for (j in i + 1 until n) {
                val mid = part(i, j)
                if (mid < 0) continue
                val c = part(j, n)
                if (c >= 0) out += suggestion(listOf(a, mid, c))
            }
        }
        return out.sortedByDescending { it.score }.take(2)
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
                // Leaving out one of a doubled letter ("occured") is a cheap, common slip.
                val omit = if (ch == prevCh) DOUBLED_LETTER else proximity.omission(ch)
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
        var prevCh = -1
        while (i < word.length) {
            val ch = Character.toLowerCase(word.codePointAt(i))
            val omit = if (ch == prevCh) DOUBLED_LETTER else proximity.omission(ch)
            prevCh = ch
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

        /** Score of a word pair the user has typed twice, before its boosts; strong dictionary pairs reach ~8.6. */
        const val PERSONAL_PREDICTION_BASE = 7f
        const val MIN_AUTOCORRECT_SCORE = 0.75f

        /** Completions need more confidence than corrections before space inserts them. */
        const val MIN_COMPLETION_SCORE = 1.5f

        /** A case/accent/apostrophe-only match wins unless something scores this much higher. */
        const val EXACT_PRIORITY = 2f

        const val SPLIT_COST = 0.5f
        const val MIN_SPLIT_FREQ = 100

        /** How much more frequent (log scale, 13 ≈ 2×) a contraction must be to replace a real word. */
        const val CONTRACTION_MARGIN = 50
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
