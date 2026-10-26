package app.weft.keyboard.predict

import app.weft.keyboard.layout.KeyGeometry
import app.weft.keyboard.layout.Layouts
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Swipe accuracy on the real dictionary, with synthetic swipes: each letter's
 * corner lands off-center by up to [WOBBLE] key widths, like a real thumb.
 * Guards against regressions and is the place to measure improvements; see
 * docs/swipe-typing.md. Skipped if the dictionary hasn't been built.
 *
 * Baseline (Oct 2026, ~380 common words): top-1 ≈ 97%, top-3 ≈ 99.7%.
 */
class SwipeAccuracyTest {

    private fun dictionaryFile(): File? = listOf(
        "src/main/assets/dictionaries/main_en_US.ekd",
        "app/src/main/assets/dictionaries/main_en_US.ekd",
    ).map(::File).firstOrNull { it.exists() }

    @Test
    fun commonWordsDecodeCorrectly() {
        val file = dictionaryFile()
        assumeTrue("dictionary not built", file != null)
        val dict = BinaryDictionary.map(file!!)
        val centers = KeyGeometry.centers(Layouts.qwerty)
        val decoder = SwipeDecoder(dict, centers, LearnedWords(null))
        val rnd = Random(7)
        val words = (1 until dict.nodeCount)
            .filter { dict.freq(it) >= 150 && !dict.isOffensive(it) }
            .map { dict.word(it) }
            .filter { it.length in 2..11 && it.all { c -> c in 'a'..'z' } }
            .shuffled(rnd)
            .take(SAMPLE)

        var top1 = 0
        var top3 = 0
        for (w in words) {
            val (xs, ys) = swipe(w, centers, rnd)
            val got = decoder.decode(xs, ys, null).map { it.word.lowercase() }
            if (got.firstOrNull() == w) top1++
            if (w in got.take(3)) top3++
        }
        val p1 = top1 * 100.0 / words.size
        val p3 = top3 * 100.0 / words.size
        println("Swipe accuracy: top-1 %.1f%%, top-3 %.1f%% (%d words)".format(p1, p3, words.size))
        assertTrue("top-1 $p1% below floor", p1 >= MIN_TOP1)
        assertTrue("top-3 $p3% below floor", p3 >= MIN_TOP3)
    }

    private fun swipe(word: String, centers: Map<Int, FloatArray>, rnd: Random): Pair<FloatArray, FloatArray> {
        val corners = ArrayList<FloatArray>()
        var last = -1
        for (c in word) {
            val k = centers[c.code] ?: continue
            if (c.code == last) continue
            last = c.code
            corners += floatArrayOf(
                k[0] + (rnd.nextFloat() * 2 - 1) * WOBBLE,
                k[1] + (rnd.nextFloat() * 2 - 1) * WOBBLE,
            )
        }
        if (corners.size == 1) corners += floatArrayOf(corners[0][0] + 0.05f, corners[0][1])
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until corners.size - 1) {
            for (s in 0 until 6) {
                val t = s / 6f
                xs += corners[i][0] + (corners[i + 1][0] - corners[i][0]) * t + (rnd.nextFloat() - 0.5f) * 0.08f
                ys += corners[i][1] + (corners[i + 1][1] - corners[i][1]) * t + (rnd.nextFloat() - 0.5f) * 0.08f
            }
        }
        xs += corners.last()[0]
        ys += corners.last()[1]
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private companion object {
        const val SAMPLE = 600
        const val WOBBLE = 0.42f // ≈ the "normal" noise level in the benchmark
        const val MIN_TOP1 = 93.0
        const val MIN_TOP3 = 99.0
    }
}
