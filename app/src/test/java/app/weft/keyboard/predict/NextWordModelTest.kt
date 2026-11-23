package app.weft.keyboard.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The Kotlin next-word model must compute exactly what tools/nextword/wlm.py computes, since the
 * accuracy measured there (and the mixing weight tuned there) assumes it. Uses a tiny random model
 * from tools/nextword/make_fixture.py and the reference's answers for a few sentences.
 */
class NextWordModelTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    private val model = NextWordModel(ByteBuffer.wrap(resource("next_word_fixture.wlm")))

    /** (context, log-probabilities, top five) for each case in the fixture. */
    private val cases = resource("next_word_fixture.txt").toString(Charsets.UTF_8).split('\n').let { l ->
        (0 until l.size / 3).map { i ->
            Triple(
                l[3 * i].split(' ').filter { it.isNotEmpty() },
                l[3 * i + 1].split(' ').map { it.toFloat() },
                l[3 * i + 2].split(' '),
            )
        }
    }

    @Test
    fun readsHeader() {
        assertEquals(60, model.vocab)
        assertEquals(1.5f, model.weight)
        assertEquals(2.0f, model.offset)
        assertEquals("fixture", model.sources)
    }

    @Test
    fun looksUpWords() {
        assertEquals(0, model.id("<s>"))
        assertEquals(2, model.id("you"))
        assertEquals(22, model.id("w0"))
        assertEquals("don't", model.word(model.id("don't")))
        assertEquals("café", model.word(model.id("café")))
        assertEquals(-1, model.id("nothere"))
        for (i in 0 until model.vocab) assertEquals(i, model.id(model.word(i)))
    }

    @Test
    fun matchesReference() {
        assertEquals(5, cases.size)
        for ((context, expected, top) in cases) {
            val p = model.predict(context)
            for (i in expected.indices) assertEquals("$context word $i", expected[i], p.logProbs[i], 2e-4f)
            assertEquals(top, p.top(5).map { it.first })
        }
    }

    @Test
    fun resumesAndRewinds() {
        // Going back and forth between sentences must give the same answers as reading them fresh.
        for ((context, expected, _) in cases.reversed() + cases) {
            val p = model.predict(context)
            for (i in expected.indices) assertEquals(expected[i], p.logProbs[i], 2e-4f)
        }
        val a = model.predict(listOf("w3"))
        assertSame(a, model.predict(listOf("W3")))
    }

    @Test
    fun unknownWordsSharePenalisedUnk() {
        val p = model.predict(listOf("w3"))
        assertEquals(p.logProbs[1] - 3f, p.logProb("somethingelse"), 1e-6f)
        assertEquals(p.logProbs[model.id("don't")], p.logProb("Don’t"), 1e-6f)
        assertTrue(p.top(3).none { it.first == "<s>" || it.first == "<unk>" })
    }
}
