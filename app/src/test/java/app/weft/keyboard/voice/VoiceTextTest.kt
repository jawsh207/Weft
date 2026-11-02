package app.weft.keyboard.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTextTest {

    private val names = setOf("Seattle", "Priya", "Monday", "Tom")

    private fun f(raw: String, before: String = "") = VoiceText.format(raw, before) { it in names }

    @Test
    fun dropsTheEnginesEndPunctuation() {
        assertEquals("See you tomorrow", f("See you tomorrow."))
        assertEquals("Are you coming", f("Are you coming?"))
        assertEquals("Yes, I'll be there", f("Yes, I'll be there."))
    }

    @Test
    fun spokenPunctuationTypesTheMark() {
        assertEquals("See you tomorrow.", f("See you tomorrow period"))
        assertEquals("Are you coming?", f("Are you coming question mark"))
        assertEquals("Wait, what!", f("Wait comma what exclamation point"))
        assertEquals("Note: buy milk; eggs", f("Note colon buy milk semicolon eggs"))
        assertEquals("Hi.\nSecond line", f("Hi period new line second line"))
        assertEquals("Done. Next thing", f("Done period next thing"))
    }

    @Test
    fun spokenPunctuationWorksEvenIfTheEngineAddedItsOwn() {
        // Moonshine may write "period." or "Period." with its own dot.
        assertEquals("See you tomorrow.", f("See you tomorrow period."))
        assertEquals("Sure. Talk later", f("Sure period. Talk later."))
    }

    @Test
    fun theWordStaysAWordWhenMeant() {
        assertEquals("The period of time", f("The period of time."))
        assertEquals("Add a comma here", f("Add a comma here."))
        assertEquals("Say period", f("Say period period"))
    }

    @Test
    fun capitalsFollowTheCursor() {
        // Start of a sentence: capitalized.
        assertEquals("Maybe tomorrow", f("maybe tomorrow"))
        assertEquals("Maybe tomorrow", f("Maybe tomorrow.", before = "Sure. "))
        assertEquals("Maybe tomorrow", f("Maybe tomorrow.", before = "Line one\n"))
        // Middle of a sentence: lowercased, unless always capitalized.
        assertEquals("maybe tomorrow", f("Maybe tomorrow.", before = "I think "))
        assertEquals("maybe tomorrow", f("Maybe tomorrow.", before = "Well,"))
        assertEquals("I think so", f("I think so.", before = "Well, "))
        assertEquals("I'm late", f("I'm late.", before = "sorry "))
        assertEquals("Seattle is nice", f("Seattle is nice.", before = "I hear "))
        assertEquals("NASA called", f("NASA called.", before = "guess what "))
    }

    @Test
    fun laterPhrasesContinueTheSentence() {
        // The engine split it into two phrases; without a spoken period it's one sentence.
        assertEquals("I think so maybe tomorrow", f("I think so. Maybe tomorrow."))
        assertEquals("Call Tom tomorrow", f("Call Tom tomorrow."))
        assertEquals("Ask Priya then go", f("Ask Priya. Then go."))
        assertEquals("Meet Mr. Smith", f("Meet Mr. Smith."))
    }

    @Test
    fun aLeadingMarkAttachesToTheTextBefore() {
        assertEquals(", maybe", f("comma maybe", before = "I think"))
        assertTrue(VoiceText.attachesToPrevious(", maybe"))
        assertTrue(VoiceText.attachesToPrevious("."))
        assertFalse(VoiceText.attachesToPrevious("maybe"))
    }

    @Test
    fun knowsWhereSentencesStart() {
        assertTrue(VoiceText.startsSentence(""))
        assertTrue(VoiceText.startsSentence(null))
        assertTrue(VoiceText.startsSentence("Done. "))
        assertTrue(VoiceText.startsSentence("Really?"))
        assertTrue(VoiceText.startsSentence("He said \"Go.\" "))
        assertTrue(VoiceText.startsSentence("Hi\n"))
        assertFalse(VoiceText.startsSentence("I think "))
        assertFalse(VoiceText.startsSentence("Well,"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", f(""))
        assertEquals("", f("   "))
    }

    @Test
    fun plainEngineOutputGetsItsCapitals() {
        // Distillery with punctuation off, or engines that return lowercase text.
        assertEquals("I think Seattle is nice. See you Monday", f("i think seattle is nice period see you monday"))
        assertEquals("but I'm late", f("but i'm late", before = "Sorry "))
        assertEquals("I'm late", f("i'm late"))
        assertEquals("ask Tom", f("ask tom", before = "please "))
        assertEquals("may I come", f("may i come", before = "so "))
    }
}
