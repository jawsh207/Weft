package app.weft.keyboard.predict

import app.weft.keyboard.layout.KeyGeometry
import app.weft.keyboard.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * Runs against tools/dictgen/testdata, built into src/test/resources/fixture_en.ekd:
 *   python3 tools/dictgen/build_dict.py --locale en_US \
 *     --aosp tools/dictgen/testdata/aosp.combined.gz --subtitles tools/dictgen/testdata/subs.txt \
 *     --allow tools/dictgen/testdata/allow.txt --leipzig tools/dictgen/testdata/leipzig \
 *     --casual tools/dictgen/testdata/casual.txt \
 *     --out app/src/test/resources/fixture_en.ekd --date 1 --min-count 1
 */
class SuggesterTest {

    private lateinit var dict: BinaryDictionary
    private lateinit var learned: LearnedWords
    private lateinit var suggester: Suggester

    @Before
    fun setUp() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("fixture_en.ekd")!!.readBytes()
        dict = BinaryDictionary(ByteBuffer.wrap(bytes))
        learned = LearnedWords(null)
        suggester = Suggester(dict, KeyProximity(KeyGeometry.centers(Layouts.qwerty)), learned)
    }

    // --- Dictionary format ----------------------------------------------------------------

    @Test
    fun readsWordsAndMeta() {
        val the = dict.find("the")
        assertTrue(the > 0)
        assertEquals("the", dict.word(the))
        assertEquals(-1, dict.find("teh"))
        assertEquals("Monday", dict.word(dict.findIgnoreCase("monday")))
        assertTrue(dict.meta.contains("\"locale\": \"en_US\""))
    }

    @Test
    fun readsBigramsFromAospAndLeipzig() {
        val next = dict.bigrams(dict.find("I")).map { dict.word(it.first) }
        assertEquals("am", next.first())
        assertTrue(dict.bigramStrength(dict.find("good"), dict.find("night")) > 0) // Leipzig
    }

    @Test
    fun flagsOffensiveWordsAndDropsShortcuts() {
        assertTrue(dict.isOffensive(dict.find("shit")))
        assertEquals(-1, dict.find("brb")) // not_a_word in AOSP
    }

    // --- Corrections ------------------------------------------------------------------------

    @Test
    fun correctsTransposition() = assertAutocorrect("teh", "the")

    @Test
    fun correctsNeighboringKey() = assertAutocorrect("thw", "the")

    @Test
    fun addsApostrophe() = assertAutocorrect("dont", "don't")

    @Test
    fun capitalizesI() = assertAutocorrect("i", "I")

    @Test
    fun capitalizesProperNoun() = assertAutocorrect("monday", "Monday")

    @Test
    fun keepsUserCapitalization() {
        assertAutocorrect("Teh", "The")
        assertAutocorrect("THW", "THE")
    }

    @Test
    fun prefersApostropheMatchOverCloserCommonWord() = assertAutocorrect("im", "I'm")

    @Test
    fun fixesRareWordsThatAreUsuallyContractions() {
        assertAutocorrect("cant", "can't")
        assertAutocorrect("wont", "won't")
    }

    @Test
    fun fixesVowelMixUps() {
        assertAutocorrect("definately", "definitely")
        assertAutocorrect("seperate", "separate")
    }

    @Test
    fun fixesMissingDoubledLetter() = assertAutocorrect("occured", "occurred")

    @Test
    fun keepsCasualLowercaseWords() {
        val r = suggester.suggest("ok", null)
        assertTrue(r.typedIsKnown)
        assertNull(r.autocorrect)
    }

    @Test
    fun leavesRealWordsAlone() {
        for (w in listOf("us", "US", "the", "The", "THE", "cafe", "its", "ill", "well")) {
            val r = suggester.suggest(w, null)
            assertTrue("$w should be known", r.typedIsKnown)
            assertNull("$w should not be corrected", r.autocorrect)
        }
    }

    @Test
    fun offersAccentedVariantWithoutForcingIt() {
        val r = suggester.suggest("cafe", null)
        assertTrue(r.suggestions.any { it.word == "café" })
        assertNull(r.autocorrect)
    }

    @Test
    fun neverSuggestsOffensiveWordsForTypos() {
        val r = suggester.suggest("shot", null)
        assertFalse(r.suggestions.any { it.word == "shit" })
        assertTrue(suggester.suggest("shit", null).typedIsKnown)
    }

    // --- Completions and predictions ------------------------------------------------------

    @Test
    fun completesPrefixOnSpace() {
        val r = suggester.suggest("keyb", null)
        assertEquals("keyboard", r.suggestions.first().word)
        assertEquals(SuggestionKind.Completion, r.suggestions.first().kind)
        assertEquals("keyboard", r.autocorrect?.word)
    }

    @Test
    fun splitsRunTogetherWords() {
        assertAutocorrect("letgo", "let go")
        assertAutocorrect("Letgo", "Let go")
    }

    @Test
    fun doesNotSplitRealWords() {
        val r = suggester.suggest("keyboard", null)
        assertTrue(r.typedIsKnown)
        assertFalse(r.suggestions.any { it.word == "key board" })
    }

    @Test
    fun keepsUnknownWordWithNoConfidentMatch() {
        assertNull(suggester.suggest("Zorblax", null).autocorrect)
    }

    @Test
    fun predictsNextWord() {
        val r = suggester.suggest("", "I")
        assertEquals("am", r.suggestions.first().word)
        assertEquals(SuggestionKind.Prediction, r.suggestions.first().kind)
        // AOSP ("good morning") and Leipzig ("good night") pairs are merged.
        val afterGood = suggester.suggest("", "good").suggestions.map { it.word }
        assertTrue(afterGood.containsAll(listOf("morning", "night")))
    }

    @Test
    fun contextBreaksTies() {
        // After "love", "you" should outrank other y- words.
        val r = suggester.suggest("yo", "love")
        assertEquals("you", r.suggestions.first().word)
    }

    // --- Learned words ------------------------------------------------------------------------

    @Test
    fun learnsWordsAfterTwoUses() {
        assertFalse(suggester.suggest("Zorblax", null).typedIsKnown)
        learned.recordUse("Zorblax")
        assertFalse(suggester.isKnown("Zorblax"))
        learned.recordUse("Zorblax")
        assertTrue(suggester.isKnown("Zorblax"))
        assertEquals("Zorblax", suggester.suggest("Zorbl", null).suggestions.first().word)
    }

    @Test
    fun explicitPickLearnsImmediately() {
        learned.recordUse("Quuxly", explicit = true)
        assertTrue(suggester.isKnown("Quuxly"))
    }

    @Test
    fun learnedWordsPersist() {
        val f = File.createTempFile("learned", ".tsv").apply { delete() }
        try {
            LearnedWords(f).apply { recordUse("Frobnitz", explicit = true); save() }
            val reloaded = LearnedWords(f).apply { load() }
            assertTrue(reloaded.isKnown("Frobnitz"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun worksWithoutDictionary() {
        val s = Suggester(null, KeyProximity(), learned)
        assertEquals(SuggestionResult.EMPTY.suggestions, s.suggest("", "I").suggestions)
        assertNull(s.suggest("teh", null).autocorrect)
    }

    // --- Context parsing --------------------------------------------------------------------

    @Test
    fun parsesContext() {
        assertEquals(WordContext("wor", "Hello"), TextContext.parse("Hello wor"))
        assertEquals(WordContext("wor", null), TextContext.parse("Hi. wor"))
        assertEquals(WordContext("", "I"), TextContext.parse("so I "))
        assertEquals(WordContext("don't", null), TextContext.parse("don't"))
        assertEquals(WordContext("", null), TextContext.parse(""))
        assertEquals(WordContext("x", null), TextContext.parse("'x"))
        assertTrue(TextContext.isSeparator(" "))
        assertTrue(TextContext.isSeparator(","))
        assertFalse(TextContext.isSeparator("a"))
        assertFalse(TextContext.isSeparator("7"))
    }

    @Test
    fun keyGeometryMatchesLayout() {
        val c = KeyGeometry.centers(Layouts.qwerty)
        assertEquals(0.5f, c['q'.code]!![0], 0.001f)
        assertEquals(1.0f, c['a'.code]!![0], 0.001f) // half-key indent
        assertNotNull(c['m'.code])
    }

    private fun assertAutocorrect(typed: String, expected: String) {
        val r = suggester.suggest(typed, null)
        assertEquals("autocorrect for '$typed' (got ${r.suggestions})", expected, r.autocorrect?.word)
    }

    // --- Learning from the user's typing -----------------------------------------------------

    @Test
    fun personalWordPairsLeadPredictions() {
        assertEquals("am", suggester.suggest("", "I").suggestions.first().word)
        repeat(3) { learned.recordWord("know", "I") }
        assertEquals("know", suggester.suggest("", "I").suggestions.first().word)
    }

    @Test
    fun predictsAfterWordsTheDictionaryDoesntKnow() {
        assertTrue(suggester.suggest("", "Weft").suggestions.isEmpty())
        repeat(2) { learned.recordWord("keyboard", "weft") }
        assertEquals(listOf("keyboard"), suggester.suggest("", "Weft").suggestions.map { it.word })
    }

    @Test
    fun removedWordsAreNeverSuggested() {
        assertEquals("the", suggester.suggest("teh", null).autocorrect?.word)
        learned.remove("the")
        val result = suggester.suggest("teh", null)
        assertFalse(result.suggestions.any { it.word.equals("the", ignoreCase = true) })
        assertFalse(result.autocorrect?.word.equals("the", ignoreCase = true))
        repeat(3) { learned.recordWord("you", "love") }
        learned.remove("you")
        assertFalse(suggester.suggest("", "love").suggestions.any { it.word == "you" })
    }

    @Test
    fun ownPairsBreakTies() {
        // "ther" could be there, their, then…; the user's habit after "over" decides.
        val before = suggester.suggest("ther", "over").suggestions.map { it.word }
        val target = before.last()
        repeat(6) { learned.recordWord(target, "over") }
        assertEquals(target, suggester.suggest("ther", "over").suggestions.first().word)
    }
}
