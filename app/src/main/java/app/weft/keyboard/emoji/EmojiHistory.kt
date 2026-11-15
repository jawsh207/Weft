package app.weft.keyboard.emoji

/**
 * Recently used emoji (most recent first) and the skin tone chosen for each
 * emoji that has one. Stored on the device only, as two short strings.
 */
class EmojiHistory(private val maxRecents: Int = 32) {

    private val recentList = ArrayList<String>()
    private val tones = LinkedHashMap<String, String>()

    /** Most recently used first. */
    val recents: List<String> get() = recentList.toList()
    /** Base emoji → the skin-tone variant last chosen for it. */
    val preferredTones: Map<String, String> get() = tones.toMap()

    /** Moves [emoji] to the front of the recents, dropping the oldest beyond the limit. */
    fun use(emoji: String) {
        recentList.remove(emoji)
        recentList.add(0, emoji)
        while (recentList.size > maxRecents) recentList.removeAt(recentList.lastIndex)
    }

    /** Remembers [variant] as the default for [base]; picking the base itself clears it. */
    fun setPreferred(base: String, variant: String) {
        if (variant == base) tones.remove(base) else tones[base] = variant
    }

    /** The variant to show for [base]: the chosen skin tone, or the base itself. */
    fun preferred(base: String): String = tones[base] ?: base

    /** The recents as one string for saving in preferences. */
    fun encodeRecents(): String = recentList.joinToString(SEP)
    /** The chosen skin tones as one string ("base=variant …") for saving in preferences. */
    fun encodeTones(): String = tones.entries.joinToString(SEP) { "${it.key}$PAIR${it.value}" }

    /** Restores what [encodeRecents] and [encodeTones] saved; nulls mean nothing saved yet. */
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
