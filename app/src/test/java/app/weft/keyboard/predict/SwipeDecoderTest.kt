package app.weft.keyboard.predict

import app.weft.keyboard.layout.KeyGeometry
import app.weft.keyboard.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

class SwipeDecoderTest {

    private val centers = KeyGeometry.centers(Layouts.qwerty)
    private lateinit var learned: LearnedWords
    private lateinit var decoder: SwipeDecoder

    @Before
    fun setUp() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("fixture_en.ekd")!!.readBytes()
        learned = LearnedWords(null)
        decoder = SwipeDecoder(BinaryDictionary(ByteBuffer.wrap(bytes)), centers, learned)
    }

    /** A swipe through [word]'s keys, each corner off-center by up to [wobble] key widths. */
    private fun swipe(word: String, wobble: Float = 0f, seed: Int = 1): Pair<FloatArray, FloatArray> {
        val rnd = Random(seed)
        val corners = ArrayList<FloatArray>()
        for (c in word.lowercase()) {
            val k = centers[c.code] ?: continue
            if (corners.isNotEmpty() && corners.last()[2] == c.code.toFloat()) continue
            corners += floatArrayOf(
                k[0] + (rnd.nextFloat() * 2 - 1) * wobble,
                k[1] + (rnd.nextFloat() * 2 - 1) * wobble,
                c.code.toFloat(),
            )
        }
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until corners.size - 1) {
            for (s in 0 until 8) {
                val t = s / 8f
                xs += corners[i][0] + (corners[i + 1][0] - corners[i][0]) * t
                ys += corners[i][1] + (corners[i + 1][1] - corners[i][1]) * t
            }
        }
        xs += corners.last()[0]
        ys += corners.last()[1]
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private fun decode(word: String, wobble: Float = 0f, previous: String? = null): List<String> {
        val (xs, ys) = swipe(word, wobble)
        return decoder.decode(xs, ys, previous).map { it.word }
    }

    @Test
    fun decodesCleanSwipes() {
        for (w in listOf("hello", "keyboard", "thing", "great", "morning", "testing", "think")) {
            assertEquals("swipe for $w", w, decode(w).firstOrNull())
        }
    }

    @Test
    fun toleratesSloppySwipes() {
        for (w in listOf("hello", "keyboard", "morning", "great")) {
            assertEquals("sloppy swipe for $w", w, decode(w, wobble = 0.3f).firstOrNull())
        }
    }

    @Test
    fun handlesDoubledLettersAndApostrophes() {
        assertEquals("good", decode("good").first())   // "oo" is one key
        assertTrue("don't" in decode("dont").take(3))     // apostrophe has no key
    }

    @Test
    fun usesContextForAmbiguousPaths() {
        // "you" after "love" from the bigram data.
        assertEquals("you", decode("you", previous = "love").first())
    }

    @Test
    fun ignoresTapsAndTinyMovements() {
        val x = floatArrayOf(4.5f, 4.6f)
        val y = floatArrayOf(0f, 0.05f)
        assertTrue(decoder.decode(x, y, null).isEmpty())
    }

    @Test
    fun includesLearnedWords() {
        learned.recordUse("Zorbl", explicit = true)
        assertTrue("Zorbl" in decode("zorbl").take(3))
    }

    @Test
    fun resampleIsEvenAndKeepsEnds() {
        val pts = SwipeDecoder.resample(floatArrayOf(0f, 1f, 3f), floatArrayOf(0f, 0f, 0f), 4)
        assertEquals(0f, pts[0][0], 1e-4f)
        assertEquals(1f, pts[1][0], 1e-4f)
        assertEquals(2f, pts[2][0], 1e-4f)
        assertEquals(3f, pts[3][0], 1e-4f)
    }
}
