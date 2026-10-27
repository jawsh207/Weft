package app.weft.keyboard.predict

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.min

/**
 * Everything Weft learns from the user's typing, kept on this device only:
 *
 *  - **New words** that aren't in the dictionary. One becomes a suggestion after
 *    it has been used twice, or once if the user picked it explicitly from the
 *    suggestion strip. Words from the system's personal dictionary (Settings ›
 *    Languages › Personal dictionary) are known immediately but are never
 *    written to our files.
 *  - **Personal frequency:** how often each word is used, so the user's own
 *    words rank above equally likely ones.
 *  - **Word pairs:** which word the user types after which, for next-word
 *    predictions and to break ties ("for"/"four", "to"/"too").
 *  - **Never correct:** words whose autocorrect the user undid.
 *  - **Removed:** words the user removed from suggestions.
 *
 * Thread-safe: reads happen while ranking off the main thread.
 */
class LearnedWords(private val file: File?, private val historyFile: File? = null) {

    private class Entry(var count: Int, var lastUsed: Long)

    private val words = ConcurrentHashMap<String, Entry>()
    private val system = ConcurrentHashMap<String, Int>()

    /** Lowercase word → uses. */
    private val usage = ConcurrentHashMap<String, Entry>()

    /** Lowercase previous word → (lowercase next word → uses). */
    private val pairs = ConcurrentHashMap<String, ConcurrentHashMap<String, Entry>>()
    private val neverCorrect: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val removed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var dirty = false

    fun load() {
        file?.takeIf { it.exists() }?.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size == 3) {
                val count = parts[1].toIntOrNull() ?: return@forEachLine
                val last = parts[2].toLongOrNull() ?: 0L
                words[parts[0]] = Entry(count, last)
            }
        }
        historyFile?.takeIf { it.exists() }?.forEachLine { line ->
            val p = line.split('\t')
            when (p[0]) {
                "u" -> if (p.size == 4) {
                    val count = p[2].toIntOrNull() ?: return@forEachLine
                    usage[p[1]] = Entry(count, p[3].toLongOrNull() ?: 0L)
                }
                "p" -> if (p.size == 5) {
                    val count = p[3].toIntOrNull() ?: return@forEachLine
                    pairs.getOrPut(p[1]) { ConcurrentHashMap() }[p[2]] = Entry(count, p[4].toLongOrNull() ?: 0L)
                }
                "n" -> if (p.size == 2) neverCorrect += p[1]
                "r" -> if (p.size == 2) removed += p[1]
            }
        }
    }

    /** Writes atomically; call when the keyboard hides. Safe to call from any thread. */
    @Synchronized
    fun save() {
        if (!dirty) return
        dirty = false
        prune()
        file?.let { f ->
            writeAtomically(f) { w ->
                for ((word, e) in words.entries.sortedBy { it.key }) w.write("$word\t${e.count}\t${e.lastUsed}\n")
            }
        }
        historyFile?.let { f ->
            writeAtomically(f) { w ->
                for ((word, e) in usage.entries.sortedBy { it.key }) w.write("u\t$word\t${e.count}\t${e.lastUsed}\n")
                for ((prev, next) in pairs.entries.sortedBy { it.key }) {
                    for ((word, e) in next.entries.sortedBy { it.key }) {
                        w.write("p\t$prev\t$word\t${e.count}\t${e.lastUsed}\n")
                    }
                }
                for (word in neverCorrect.sorted()) w.write("n\t$word\n")
                for (word in removed.sorted()) w.write("r\t$word\n")
            }
        }
    }

    // --- New words -----------------------------------------------------------------------

    fun recordUse(word: String, explicit: Boolean = false, now: Long = System.currentTimeMillis()) {
        if (!isStorable(word)) return
        removed -= word.lowercase()
        val e = words.getOrPut(word) { Entry(0, now) }
        e.count += if (explicit) MIN_USES else 1
        e.lastUsed = now
        dirty = true
    }

    /** True if [word] has been recorded at all, even once. */
    fun has(word: String): Boolean = words.containsKey(word)

    fun forget(word: String) {
        if (words.remove(word) != null) dirty = true
    }

    fun setSystemWords(entries: Map<String, Int>) {
        system.clear()
        entries.forEach { (w, f) -> if (isStorable(w)) system[w] = f.coerceIn(1, 255) }
    }

    /** True if [word] is a known learned or personal word (case-insensitive). */
    fun isKnown(word: String): Boolean = candidates().any { it.first.equals(word, ignoreCase = true) }

    /** Known words with a 1..255 frequency comparable to dictionary words. */
    fun candidates(): Sequence<Pair<String, Int>> =
        words.entries.asSequence()
            .filter { it.value.count >= MIN_USES && !isRemoved(it.key) }
            .map { it.key to (130 + 15 * it.value.count).coerceAtMost(220) } +
            system.entries.asSequence()
                .filter { !isRemoved(it.key) }
                .map { it.key to (it.value / 2 + 100).coerceAtMost(220) }

    val size: Int get() = words.size

    // --- Personal frequency and word pairs ----------------------------------------------

    /**
     * Records that [word] was typed (finished with space, punctuation, a pick
     * or a swipe) after [previous], or at the start of a phrase if null.
     */
    fun recordWord(word: String, previous: String?, now: Long = System.currentTimeMillis()) {
        val w = word.lowercase()
        if (!isStorable(w) && w.length != 1) return
        if (w.none { it.isLetter() }) return
        usage.getOrPut(w) { Entry(0, now) }.apply { count++; lastUsed = now }
        val p = previous?.lowercase()
        if (p != null && (isStorable(p) || p.length == 1) && p.any { it.isLetter() }) {
            pairs.getOrPut(p) { ConcurrentHashMap() }.getOrPut(w) { Entry(0, now) }.apply { count++; lastUsed = now }
        }
        dirty = true
    }

    /** Takes back a [recordWord], e.g. when an autocorrect is undone. */
    fun retractWord(word: String, previous: String?) {
        val w = word.lowercase()
        usage[w]?.let { if (--it.count <= 0) usage.remove(w) }
        val p = previous?.lowercase() ?: return
        val next = pairs[p] ?: return
        next[w]?.let { if (--it.count <= 0) next.remove(w) }
        if (next.isEmpty()) pairs.remove(p)
        dirty = true
    }

    /**
     * Ranking bonus for a word the user types often, in the same units as the
     * suggesters' scores (a dictionary word's frequency / 25.5 ranges 0–10).
     */
    fun boost(word: String): Float {
        val n = usage[word.lowercase()]?.count ?: return 0f
        return min(MAX_BOOST, BOOST_PER_LOG_USE * ln(1f + n))
    }

    /** Bonus for [word] coming after [previous] in the user's own typing. */
    fun pairBoost(previous: String?, word: String): Float {
        val p = previous?.lowercase() ?: return 0f
        val n = pairs[p]?.get(word.lowercase())?.count ?: return 0f
        return min(MAX_PAIR_BOOST, PAIR_BOOST_PER_USE * n)
    }

    /** Words the user has typed after [previous] at least [PAIR_MIN_USES] times, most used first. */
    fun nextWords(previous: String): List<Pair<String, Int>> =
        pairs[previous.lowercase()].orEmpty().entries
            .filter { it.value.count >= PAIR_MIN_USES && !isRemoved(it.key) }
            .sortedWith(compareByDescending<Map.Entry<String, Entry>> { it.value.count }.thenByDescending { it.value.lastUsed })
            .map { it.key to it.value.count }

    // --- Never correct, removed -------------------------------------------------------------

    /** The user undid an autocorrect of [word] (as typed), so it stays as typed from now on. */
    fun keepAsTyped(word: String) {
        if (neverCorrect.add(word.lowercase())) dirty = true
    }

    fun isKeptAsTyped(word: String): Boolean = word.lowercase() in neverCorrect

    /**
     * Removes [word] from suggestions and predictions: forgets it as a new
     * word and drops what was learned about it. Dictionary words are hidden;
     * typing or picking the word explicitly again brings it back.
     */
    fun remove(word: String) {
        val w = word.lowercase()
        removed += w
        words.keys.filter { it.equals(word, ignoreCase = true) }.forEach { words.remove(it) }
        usage.remove(w)
        pairs.remove(w)
        pairs.values.forEach { it.remove(w) }
        pairs.entries.removeIf { it.value.isEmpty() }
        dirty = true
    }

    fun isRemoved(word: String): Boolean = removed.isNotEmpty() && word.lowercase() in removed

    /** Brings back a removed word, e.g. when the user picks it as typed. */
    fun restore(word: String) {
        if (removed.remove(word.lowercase())) dirty = true
    }

    /** Forgets everything. */
    fun clear() {
        words.clear()
        usage.clear()
        pairs.clear()
        neverCorrect.clear()
        removed.clear()
        dirty = true
    }

    /** Number of word pairs learned (for settings). */
    val pairCount: Int get() = pairs.values.sumOf { it.size }

    private fun prune() {
        if (words.size > MAX_WORDS) {
            words.entries
                .sortedWith(compareBy({ it.value.count }, { it.value.lastUsed }))
                .take(words.size - MAX_WORDS)
                .forEach { words.remove(it.key) }
        }
        if (usage.size > MAX_USAGE) {
            usage.entries
                .sortedWith(compareBy({ it.value.count }, { it.value.lastUsed }))
                .take(usage.size - MAX_USAGE)
                .forEach { usage.remove(it.key) }
        }
        if (pairCount > MAX_PAIRS) {
            pairs.entries
                .flatMap { (prev, next) -> next.entries.map { Triple(prev, it.key, it.value) } }
                .sortedWith(compareBy({ it.third.count }, { it.third.lastUsed }))
                .take(pairCount - MAX_PAIRS)
                .forEach { (prev, word, _) -> pairs[prev]?.remove(word) }
            pairs.entries.removeIf { it.value.isEmpty() }
        }
    }

    private fun writeAtomically(f: File, body: (java.io.Writer) -> Unit) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.bufferedWriter().use(body)
        tmp.renameTo(f)
    }

    private fun isStorable(word: String): Boolean =
        word.length in 2..32 && word.none { it == '\t' || it == '\n' || it.isWhitespace() }

    companion object {
        const val MIN_USES = 2
        const val MAX_WORDS = 5000
        const val MAX_USAGE = 20_000
        const val MAX_PAIRS = 30_000
        const val PAIR_MIN_USES = 2
        const val BOOST_PER_LOG_USE = 0.35f
        const val MAX_BOOST = 1.5f
        const val PAIR_BOOST_PER_USE = 0.6f
        const val MAX_PAIR_BOOST = 3f

        /** File names in the app's private storage. */
        const val WORDS_FILE = "learned_words.tsv"
        const val HISTORY_FILE = "typing_history.tsv"
    }
}
