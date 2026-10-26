package app.weft.keyboard.emoji

/** One emoji as the picker shows it, plus its single-tone skin variants and search keywords. */
data class Emoji(
    val base: String,
    val name: String,
    val variants: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    /** Weft's hand-picked keywords → rank (0 = best match for that keyword). */
    val picks: Map<String, Int> = emptyMap(),
)

data class EmojiGroup(val name: String, val emoji: List<Emoji>)

/**
 * The emoji list from assets/emoji/emoji.txt (built by tools/emojigen from
 * Unicode's emoji-test.txt). Emoji the phone's font can't draw are dropped at
 * load time, so the picker never shows empty boxes.
 */
class EmojiCatalog(val groups: List<EmojiGroup>) {

    private val all: List<Emoji> = groups.flatMap { it.emoji }
    private val byText: Map<String, Emoji> = buildMap {
        for (e in all) {
            put(e.base, e)
            e.variants.forEach { put(it, e) }
        }
    }

    val size: Int get() = all.size

    /** The catalog entry for [text], which may be a base emoji or a skin-tone variant. */
    fun find(text: String): Emoji? = byText[text]

    /**
     * Search by name and keyword. Every word typed must start a word of the
     * emoji's name or keywords ("thu up" finds "thumbs up", "lol" finds 😂).
     * Ranking: exact name, Weft's hand-picked keywords (in their listed order,
     * so "lol" → 😂 🤣), whole words in the name, an exact CLDR keyword, name
     * prefix, then anything else; shorter names first within each, so the plain
     * emoji leads ("heart" → ❤️ before 💘).
     */
    fun search(query: String, limit: Int = 60): List<Emoji> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(WORD_BREAK).filter { it.isNotEmpty() }
        return all.asSequence()
            .mapNotNull { e ->
                val name = e.name.lowercase()
                val nameWords = name.split(WORD_BREAK)
                val keywords = e.keywords.map { it.lowercase() }
                val words = nameWords + keywords.flatMap { it.split(WORD_BREAK) } + e.picks.keys
                if (!tokens.all { t -> words.any { it.startsWith(t) } }) return@mapNotNull null
                val pick = e.picks[q]
                val rank = when {
                    name == q -> 0
                    pick != null -> 1
                    tokens.all { t -> t in nameWords } -> 2
                    q in keywords -> 3
                    name.startsWith(q) -> 4
                    else -> 5
                }
                Triple(rank, pick ?: name.length, e)
            }
            .sortedWith(compareBy({ it.first }, { it.second })) // stable within ties
            .take(limit)
            .map { it.third }
            .toList()
    }

    companion object {
        private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")

        /**
         * Parses the asset. [isSupported] reports whether the system emoji font
         * can draw a given emoji; unsupported ones (and their variants) are dropped.
         */
        fun parse(text: String, isSupported: (String) -> Boolean = { true }): EmojiCatalog {
            val groups = ArrayList<EmojiGroup>()
            var name: String? = null
            var current = ArrayList<Emoji>()
            fun flush() {
                val n = name
                if (n != null && current.isNotEmpty()) groups += EmojiGroup(n, current)
            }
            for (line in text.lineSequence()) {
                when {
                    line.isBlank() || line.startsWith("#") -> Unit
                    line.startsWith("@") -> {
                        flush()
                        name = line.substring(1).trim()
                        current = ArrayList()
                    }
                    else -> {
                        val parts = line.split('\t')
                        if (parts.size < 2 || name == null) continue
                        val base = parts[0]
                        if (!isSupported(base)) continue
                        val variants = parts.getOrNull(2).orEmpty()
                            .split(' ')
                            .filter { it.isNotEmpty() && isSupported(it) }
                        val keywords = parts.getOrNull(3).orEmpty()
                            .split('|')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        val picks = parts.getOrNull(4).orEmpty()
                            .split('|')
                            .mapNotNull { p ->
                                val i = p.lastIndexOf('#')
                                if (i <= 0) null else p.substring(0, i).trim() to (p.substring(i + 1).trim().toIntOrNull() ?: 0)
                            }
                            .toMap()
                        current += Emoji(base, parts[1], variants, keywords, picks)
                    }
                }
            }
            flush()
            return EmojiCatalog(groups)
        }

        /** A single unnamed group, for when the asset is missing. */
        fun fromList(group: String, emoji: List<String>) =
            EmojiCatalog(listOf(EmojiGroup(group, emoji.map { Emoji(it, "") })))
    }
}
