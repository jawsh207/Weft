package app.weft.keyboard.emoji

/** One emoji as the picker shows it, plus its single-tone skin variants. */
data class Emoji(
    val base: String,
    val name: String,
    val variants: List<String> = emptyList(),
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
     * Name search. Every word typed must start a word of the emoji's name
     * ("thu up" finds "thumbs up"). Names that start with the query rank first.
     */
    fun search(query: String, limit: Int = 60): List<Emoji> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(WORD_BREAK).filter { it.isNotEmpty() }
        return all.asSequence()
            .mapNotNull { e ->
                val name = e.name.lowercase()
                val words = name.split(WORD_BREAK)
                if (tokens.all { t -> words.any { it.startsWith(t) } }) {
                    val rank = when {
                        name == q -> 0
                        name.startsWith(q) -> 1
                        words.first().startsWith(tokens.first()) -> 2
                        else -> 3
                    }
                    rank to e
                } else {
                    null
                }
            }
            .sortedBy { it.first } // stable: keeps Unicode order within a rank
            .take(limit)
            .map { it.second }
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
                        current += Emoji(base, parts[1], variants)
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
