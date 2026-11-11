package app.weft.keyboard.predict

import app.weft.keyboard.layout.KeyGeometry
import app.weft.keyboard.layout.Layouts
import org.junit.Assert.assertEquals
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
 * Results (Nov 2026, ~380 common words), top-1 / top-3: straight 96.8% / 99.7%,
 * natural 93.4% / 99.7%, hurried 84.1% / 96.6%. Before tuning on natural swipes the
 * decoder managed 92.6% and 67.9% top-1 on the natural and hurried sets.
 * With timestamps (timingHelpsTellLookAlikesApart, 999 words): 92.1% → 94.5%
 * relaxed, 77.4% → 80.8% hurried.
 */
class SwipeAccuracyTest {

    private fun dictionaryFile(): File? = RealDictionary.file

    @Test
    fun commonWordsDecodeCorrectly() {
        val file = dictionaryFile()
        assumeTrue("dictionary not built", file != null)
        val dict = BinaryDictionary.map(file!!)
        val centers = KeyGeometry.centers(Layouts.qwerty)
        val decoder = SwipeDecoder(dict, centers, LearnedWords(null))
        val rnd = Random(7)
        val words = sampleWords(dict, 150)
            .shuffled(rnd)
            .take(SAMPLE)

        val sim = SwipeSimulator(centers, rnd)
        val (p1, p3) = score(decoder, words) { sim.straight(it, WOBBLE) }
        println("Swipe accuracy: top-1 %.1f%%, top-3 %.1f%% (%d words)".format(p1, p3, words.size))
        assertTrue("top-1 $p1% below floor", p1 >= MIN_TOP1)
        assertTrue("top-3 $p3% below floor", p3 >= MIN_TOP3)

        // Human-like swipes: curved, corner-cutting, shifted, overshooting (SwipeSimulator).
        val natural = SwipeSimulator(centers, Random(11))
        for ((label, sloppiness, floor) in listOf(Triple("natural", 1f, MIN_NATURAL), Triple("hurried", 1.5f, MIN_HURRIED))) {
            val (n1, n3) = score(decoder, words) { natural.natural(it, sloppiness) }
            println("Swipe accuracy ($label): top-1 %.1f%%, top-3 %.1f%%".format(n1, n3))
            assertTrue("$label top-1 $n1% below floor", n1 >= floor)
        }
    }

    /**
     * A user whose thumb always lands about a third of a key low and to the left.
     * After learning from swipes of other words, the decoder should do clearly
     * better on this user than before.
     */
    @Test
    fun learnsAUsersSteadyOffset() {
        val file = dictionaryFile()
        assumeTrue("dictionary not built", file != null)
        val dict = BinaryDictionary.map(file!!)
        val centers = KeyGeometry.centers(Layouts.qwerty)
        val learned = LearnedWords(null)
        val decoder = SwipeDecoder(dict, centers, learned)
        val all = sampleWords(dict, 150)
            .shuffled(Random(21))
        val training = all.take(150)
        val testWords = all.drop(150).take(SAMPLE)
        val sim = SwipeSimulator(centers, Random(22))
        fun shifted(w: String) = sim.natural(w)?.let { s ->
            SwipeSimulator.Swipe(FloatArray(s.xs.size) { s.xs[it] - 0.3f }, FloatArray(s.ys.size) { s.ys[it] + 0.35f })
        }
        val (before, _) = score(decoder, testWords, ::shifted)
        for (w in training) {
            val s = shifted(w) ?: continue
            val first = decoder.decode(s.xs, s.ys, null).firstOrNull()?.word ?: continue
            decoder.learnFrom(w, first, s.xs, s.ys) // the user kept (or picked) the right word
        }
        val (after, _) = score(decoder, testWords, ::shifted)
        println("Swipe accuracy (offset thumb): top-1 %.1f%% before learning, %.1f%% after".format(before, after))
        assertTrue("learning should help an offset thumb: $before -> $after", after >= before + MIN_OFFSET_GAIN)
    }

    @Test
    fun remembersWhichLookAlikeTheUserMeans() {
        val file = dictionaryFile()
        assumeTrue("dictionary not built", file != null)
        val dict = BinaryDictionary.map(file!!)
        val centers = KeyGeometry.centers(Layouts.qwerty)
        val learned = LearnedWords(null)
        val decoder = SwipeDecoder(dict, centers, learned)
        val s = SwipeSimulator(centers, Random(5)).straight("for", 0f)!!
        val guesses = decoder.decode(s.xs, s.ys, null).map { it.word }
        val first = guesses[0]
        val other = guesses[1]
        repeat(2) { decoder.learnFrom(other, first, s.xs, s.ys) } // the user picked the other one twice
        assertEquals(other, decoder.decode(s.xs, s.ys, null).first().word)
    }

    /**
     * Timing: the same swipes decoded without and with their timestamps. Where the
     * finger slowed tells look-alike words apart. Nobody knows exactly how much real
     * thumbs slow on straight stretches, so this also runs a "stingy" thumb (barely
     * slows, hesitates often) and a "steady" one (hardly slows at all): timing has
     * to help the first two and must not hurt the third.
     */
    @Test
    fun timingHelpsTellLookAlikesApart() {
        val file = dictionaryFile()
        assumeTrue("dictionary not built", file != null)
        val dict = BinaryDictionary.map(file!!)
        val centers = KeyGeometry.centers(Layouts.qwerty)
        val decoder = SwipeDecoder(dict, centers, LearnedWords(null))
        val words = sampleWords(dict, 135)
            .shuffled(Random(7))
            .take(1200)
        val paces = listOf(
            Triple("typical", SwipeSimulator.Pace(), MIN_TIMING_GAIN),
            Triple("stingy", SwipeSimulator.Pace(straightDip = 0.12f, straightSpread = 0.15f, hesitation = 0.25f), MIN_TIMING_GAIN),
            Triple("steady", SwipeSimulator.Pace(straightDip = 0f, straightSpread = 0f, cornerDip = 0.3f, hesitation = 0f), 0.0),
        )
        for ((label, pace, minGain) in paces) {
            for ((speed, sloppiness) in listOf("relaxed" to 1f, "hurried" to 1.5f)) {
                val sim = SwipeSimulator(centers, Random(11))
                var without = 0
                var with = 0
                var n = 0
                for (w in words) {
                    val s = sim.timed(w, sloppiness, pace) ?: continue
                    n++
                    if (decoder.decode(s.xs, s.ys, null).firstOrNull()?.word?.lowercase() == w) without++
                    if (decoder.decode(s.xs, s.ys, null, times = s.times).firstOrNull()?.word?.lowercase() == w) with++
                }
                val a = without * 100.0 / n
                val b = with * 100.0 / n
                println("Swipe timing ($label thumb, $speed): top-1 %.1f%% without timing, %.1f%% with (%d words)".format(a, b, n))
                assertTrue("timing should help a $label $speed thumb: $a -> $b", b >= a + minGain)
            }
        }
    }

    /** One straight line from p to t: only where the finger pauses says pot, pit or put. */
    @Test
    fun aPauseOnALetterPicksItsWord() {
        val file = dictionaryFile()
        assumeTrue("dictionary not built", file != null)
        val dict = BinaryDictionary.map(file!!)
        val centers = KeyGeometry.centers(Layouts.qwerty)
        val decoder = SwipeDecoder(dict, centers, LearnedWords(null))
        val p = centers['p'.code]!!
        val t = centers['t'.code]!!
        for (word in listOf("pot", "pit", "put")) {
            val pauseX = centers[word[1].code]!![0]
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            val times = ArrayList<Long>()
            var clock = 0L
            var x = p[0]
            while (x > t[0]) {
                xs += x; ys += p[1]; times += clock
                // 10 ms per step, lingering for about half a second around the letter.
                x -= if (kotlin.math.abs(x - pauseX) < 0.15f) 0.006f else 0.2f
                clock += 10
            }
            xs += t[0]; ys += t[1]; times += clock
            val got = decoder.decode(xs.toFloatArray(), ys.toFloatArray(), null, times = times.toLongArray())
            assertEquals("pausing on '${word[1]}'", word, got.firstOrNull()?.word)
        }
    }

    /**
     * Common lowercase words to swipe. For comparing two dictionaries on the same
     * words, WEFT_SWIPE_WORDS_<minFreq> can name a file with one word per line.
     */
    private fun sampleWords(dict: BinaryDictionary, minFreq: Int): List<String> {
        val fixed = System.getenv("WEFT_SWIPE_WORDS_$minFreq")?.let(::File)?.takeIf { it.exists() }
        if (fixed != null) {
            val known = (1 until dict.nodeCount).filter { dict.freq(it) > 0 }.map { dict.word(it) }.toSet()
            val listed = fixed.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            println("Swipe words: ${listed.size} listed, ${listed.count { it !in known }} not in this dictionary")
            return listed.filter { it in known }
        }
        return (1 until dict.nodeCount)
            .filter { dict.freq(it) >= minFreq && !dict.isOffensive(it) }
            .map { dict.word(it) }
            .filter { it.length in 2..11 && it.all { c -> c in 'a'..'z' } }
    }

    private fun score(decoder: SwipeDecoder, words: List<String>, make: (String) -> SwipeSimulator.Swipe?): Pair<Double, Double> {
        var top1 = 0
        var top3 = 0
        var n = 0
        for (w in words) {
            val swipe = make(w) ?: continue
            n++
            val got = decoder.decode(swipe.xs, swipe.ys, null).map { it.word.lowercase() }
            if (got.firstOrNull() == w) top1++
            if (w in got.take(3)) top3++
        }
        return top1 * 100.0 / n to top3 * 100.0 / n
    }

    private companion object {
        const val SAMPLE = 600
        const val WOBBLE = 0.42f // ≈ the "normal" noise level in the benchmark
        const val MIN_TOP1 = 93.0
        const val MIN_TOP3 = 99.0
        const val MIN_NATURAL = 90.0
        const val MIN_HURRIED = 78.0
        const val MIN_OFFSET_GAIN = 3.0
        const val MIN_TIMING_GAIN = 0.5
    }
}
