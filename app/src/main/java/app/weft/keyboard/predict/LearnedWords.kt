package app.weft.keyboard.predict

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Words the user types that aren't in the dictionary, kept on this device only.
 *
 * A word becomes a suggestion after it has been used twice, or once if the user
 * picked it explicitly from the suggestion strip. Words from the system's
 * personal dictionary (Settings › Languages › Personal dictionary) are known
 * immediately but are never written to our file.
 */
class LearnedWords(private val file: File?) {

    private class Entry(var count: Int, var lastUsed: Long)

    private val words = ConcurrentHashMap<String, Entry>()
    private val system = ConcurrentHashMap<String, Int>()

    @Volatile
    private var dirty = false

    fun load() {
        val f = file ?: return
        if (!f.exists()) return
        f.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size == 3) {
                val count = parts[1].toIntOrNull() ?: return@forEachLine
                val last = parts[2].toLongOrNull() ?: 0L
                words[parts[0]] = Entry(count, last)
            }
        }
    }

    /** Writes atomically; call when the keyboard hides. */
    fun save() {
        val f = file ?: return
        if (!dirty) return
        dirty = false
        prune()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            for ((word, e) in words.entries.sortedBy { it.key }) {
                w.write("$word\t${e.count}\t${e.lastUsed}\n")
            }
        }
        tmp.renameTo(f)
    }

    fun recordUse(word: String, explicit: Boolean = false, now: Long = System.currentTimeMillis()) {
        if (!isStorable(word)) return
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

    fun clear() {
        words.clear()
        dirty = true
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
            .filter { it.value.count >= MIN_USES }
            .map { it.key to (130 + 15 * it.value.count).coerceAtMost(220) } +
            system.entries.asSequence().map { it.key to (it.value / 2 + 100).coerceAtMost(220) }

    val size: Int get() = words.size

    private fun prune() {
        if (words.size <= MAX_WORDS) return
        words.entries
            .sortedWith(compareBy({ it.value.count }, { it.value.lastUsed }))
            .take(words.size - MAX_WORDS)
            .forEach { words.remove(it.key) }
    }

    private fun isStorable(word: String): Boolean =
        word.length in 2..32 && word.none { it == '\t' || it == '\n' || it.isWhitespace() }

    companion object {
        const val MIN_USES = 2
        const val MAX_WORDS = 5000
    }
}
