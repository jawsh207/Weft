package app.weft.keyboard.emoji

/**
 * Recently used emoji (most recent first) and the skin tone chosen for each
 * emoji that has one. Stored on the device only, as two short strings.
 */
class EmojiHistory(private val maxRecents: Int = 32) {

    private val recentList = ArrayList<String>()
    private val tones = LinkedHashMap<String, String>()

    val recents: List<String> get() = recentList.toList()
    val preferredTones: Map<String, String> get() = tones.toMap()

    fun use(emoji: String) {
        recentList.remove(emoji)
        recentList.add(0, emoji)
        while (recentList.size > maxRecents) recentList.removeAt(recentList.lastIndex)
    }

    /** Remembers [variant] as the default for [base]; picking the base itself clears it. */
    fun setPreferred(base: String, variant: String) {
        if (variant == base) tones.remove(base) else tones[base] = variant
    }

    fun preferred(base: String): String = tones[base] ?: base

    fun encodeRecents(): String = recentList.joinToString(SEP)
    fun encodeTones(): String = tones.entries.joinToString(SEP) { "${it.key}$PAIR${it.value}" }

    fun decode(recents: String?, preferredTones: String?) {
        recentList.clear()
        tones.clear()
        recents.orEmpty().split(SEP).filter { it.isNotEmpty() }.take(maxRecents).forEach { recentList += it }
        preferredTones.orEmpty().split(SEP).forEach { entry ->
            val i = entry.indexOf(PAIR)
            if (i > 0) tones[entry.substring(0, i)] = entry.substring(i + 1)
        }
    }

    private companion object {
        const val SEP = " "
        const val PAIR = "="
    }
}
