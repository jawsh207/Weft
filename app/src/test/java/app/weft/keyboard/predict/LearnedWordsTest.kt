package app.weft.keyboard.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LearnedWordsTest {

    @Test
    fun savesAndReloadsEverything() {
        val dir = Files.createTempDirectory("weft").toFile()
        val a = LearnedWords(dir.resolve("words.tsv"), dir.resolve("history.tsv"))
        a.recordUse("weftish", explicit = true)
        repeat(3) { a.recordWord("you", "see") }
        a.keepAsTyped("Teh")
        a.remove("duck")
        a.save()

        val b = LearnedWords(dir.resolve("words.tsv"), dir.resolve("history.tsv"))
        b.load()
        assertTrue(b.isKnown("weftish"))
        assertEquals(listOf("you" to 3), b.nextWords("See"))
        assertTrue(b.boost("you") > 0f)
        assertTrue(b.isKeptAsTyped("teh"))
        assertTrue(b.isRemoved("Duck"))
    }

    @Test
    fun boostsGrowWithUseButStayBounded() {
        val l = LearnedWords(null)
        assertEquals(0f, l.boost("the"), 0f)
        l.recordWord("the", null)
        val once = l.boost("the")
        repeat(10) { l.recordWord("the", null) }
        assertTrue(l.boost("the") > once)
        repeat(10_000) { l.recordWord("the", null) }
        assertEquals(LearnedWords.MAX_BOOST, l.boost("the"), 1e-4f)

        assertEquals(0f, l.pairBoost("see", "you"), 0f)
        repeat(100) { l.recordWord("you", "see") }
        assertEquals(LearnedWords.MAX_PAIR_BOOST, l.pairBoost("SEE", "You"), 1e-4f)
    }

    @Test
    fun nextWordsNeedTwoUses() {
        val l = LearnedWords(null)
        l.recordWord("soon", "talk")
        assertTrue(l.nextWords("talk").isEmpty())
        l.recordWord("soon", "talk")
        assertEquals(listOf("soon" to 2), l.nextWords("talk"))
    }

    @Test
    fun retractUndoesARecord() {
        val l = LearnedWords(null)
        l.recordWord("the", "see")
        l.retractWord("the", "see")
        assertEquals(0f, l.boost("the"), 0f)
        assertEquals(0f, l.pairBoost("see", "the"), 0f)
    }

    @Test
    fun removeForgetsTheWordEverywhere() {
        val l = LearnedWords(null)
        l.recordUse("ducking", explicit = true)
        repeat(3) { l.recordWord("ducking", "so") }
        repeat(3) { l.recordWord("cold", "ducking") }
        l.remove("Ducking")
        assertFalse(l.isKnown("ducking"))
        assertTrue(l.nextWords("so").isEmpty())
        assertTrue(l.nextWords("ducking").isEmpty())
        assertEquals(0f, l.boost("ducking"), 0f)
        // Learning it again on purpose brings it back.
        l.recordUse("ducking", explicit = true)
        assertTrue(l.isKnown("ducking"))
    }

    @Test
    fun ignoresNonWords() {
        val l = LearnedWords(null)
        l.recordWord("123", null)
        l.recordWord("two words", null)
        l.recordWord("a", "b")
        assertEquals(0f, l.boost("123"), 0f)
        assertEquals(0f, l.boost("two words"), 0f)
        assertTrue(l.boost("a") > 0f)
    }

    @Test
    fun clearForgetsEverything() {
        val l = LearnedWords(null)
        l.recordUse("weftish", explicit = true)
        repeat(2) { l.recordWord("you", "see") }
        l.keepAsTyped("teh")
        l.remove("duck")
        l.clear()
        assertFalse(l.isKnown("weftish"))
        assertTrue(l.nextWords("see").isEmpty())
        assertFalse(l.isKeptAsTyped("teh"))
        assertFalse(l.isRemoved("duck"))
        assertEquals(0, l.pairCount)
    }
}
