package app.weft.keyboard.predict

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Edit costs for fuzzy matching. Substituting a letter for one physically next
 * to it on the keyboard is cheap ("thw" → "the"); far-apart letters cost a full
 * edit. Accent-only differences are nearly free ("cafe" → "café").
 *
 * @param centers key centers in key-width units, keyed by lowercase code point.
 */
class KeyProximity(private val centers: Map<Int, FloatArray> = emptyMap()) {

    private val foldCache = ConcurrentHashMap<Int, Int>()

    /** Cost of the user typing [typed] when they meant [intended]. Both lowercase. */
    fun substitution(typed: Int, intended: Int): Float {
        if (typed == intended) return 0f
        val ft = fold(typed)
        val fi = fold(intended)
        if (ft == fi) return ACCENT
        // Vowel mix-ups are the most common spelling errors ("definately", "seperate").
        val vowelSwap = if (ft in VOWELS && fi in VOWELS) VOWEL else FAR
        val a = centers[typed] ?: return vowelSwap
        val b = centers[fi] ?: return vowelSwap
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val d = sqrt(dx * dx + dy * dy)
        val distance = when {
            d <= 1.15f -> NEIGHBOR
            d <= 1.6f -> DIAGONAL
            else -> FAR
        }
        return minOf(distance, vowelSwap)
    }

    /** Cost of the user leaving out [intended] entirely. */
    fun omission(intended: Int): Float = when (intended) {
        '\''.code -> 0.1f // "dont" → "don't"
        '-'.code -> 0.3f
        else -> 1f
    }

    /** Lowercase base letter with accents removed. */
    fun fold(cp: Int): Int {
        if (cp < 0x80) return Character.toLowerCase(cp)
        return foldCache.getOrPut(cp) {
            val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
            Character.toLowerCase(decomposed.codePointAt(0))
        }
    }

    companion object {
        const val ACCENT = 0.15f
        const val NEIGHBOR = 0.45f
        const val DIAGONAL = 0.7f
        const val VOWEL = 0.6f
        const val FAR = 1f
        private val VOWELS = setOf('a'.code, 'e'.code, 'i'.code, 'o'.code, 'u'.code)
    }
}
