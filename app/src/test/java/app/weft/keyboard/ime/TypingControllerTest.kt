package app.weft.keyboard.ime

import app.weft.keyboard.layout.KeyGeometry
import app.weft.keyboard.layout.Layouts
import app.weft.keyboard.predict.BinaryDictionary
import app.weft.keyboard.predict.KeyProximity
import app.weft.keyboard.predict.LearnedWords
import app.weft.keyboard.predict.Suggester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

class TypingControllerTest {

    /** A text field with the cursor at the end. */
    private class FakeEditor : TextEditor {
        val text = StringBuilder()
        override fun textBeforeCursor(length: Int) = text.substring(maxOf(0, text.length - length))
        override fun deleteBefore(length: Int) {
            text.setLength(maxOf(0, text.length - length))
        }
        override fun insert(text: String) {
            this.text.append(text)
        }
        override fun toString() = text.toString()
    }

    private lateinit var editor: FakeEditor
    private lateinit var learned: LearnedWords
    private lateinit var controller: TypingController

    @Before
    fun setUp() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("fixture_en.ekd")!!.readBytes()
        learned = LearnedWords(null)
        controller = TypingController(learned).apply {
            suggester = Suggester(
                BinaryDictionary(ByteBuffer.wrap(bytes)),
                KeyProximity(KeyGeometry.centers(Layouts.qwerty)),
                learned,
            )
        }
        editor = FakeEditor()
    }

    private fun type(word: String) = word.forEach { controller.onText(editor, it.toString()) }

    @Test
    fun autocorrectsOnSpaceAndUndoesWithBackspace() {
        type("teh")
        controller.onSeparator(editor, " ")
        assertEquals("the ", editor.toString())
        assertTrue(controller.onBackspace(editor))
        assertEquals("teh ", editor.toString())
    }

    @Test
    fun undoneWordIsNotCorrectedAgain() {
        type("teh")
        controller.onSeparator(editor, " ")
        controller.onBackspace(editor)
        type("teh")
        controller.onSeparator(editor, " ")
        assertEquals("teh teh ", editor.toString())
    }

    @Test
    fun backspaceAfterOtherTypingDeletesNormally() {
        type("teh")
        controller.onSeparator(editor, " ")
        type("x")
        assertFalse(controller.onBackspace(editor))
    }

    @Test
    fun autocorrectsBeforePunctuation() {
        type("dont")
        controller.onSeparator(editor, ",")
        assertEquals("don't,", editor.toString())
    }

    @Test
    fun pickReplacesWordAndPunctuationAttaches() {
        type("keyb")
        controller.onPick(editor, SuggestionChip("keyboard"))
        assertEquals("keyboard ", editor.toString())
        controller.onSeparator(editor, ".")
        assertEquals("keyboard. ", editor.toString())
    }

    @Test
    fun noDoubleSpaceAfterPick() {
        type("keyb")
        controller.onPick(editor, SuggestionChip("keyboard"))
        controller.onSeparator(editor, " ")
        assertEquals("keyboard ", editor.toString())
    }

    @Test
    fun spaceInsertsMiddleSuggestion() {
        type("keyb")
        val chips = controller.publish(controller.compute(controller.context(editor)))
        assertEquals("keyboard", chips[1]?.text)
        controller.onSeparator(editor, " ")
        assertEquals("keyboard ", editor.toString())
    }

    @Test
    fun spaceSplitsRunTogetherWords() {
        type("letgo")
        controller.onSeparator(editor, " ")
        assertEquals("let go ", editor.toString())
        assertTrue(controller.onBackspace(editor))
        assertEquals("letgo ", editor.toString())
    }

    @Test
    fun knownWordStaysInMiddle() {
        type("the")
        val chips = controller.publish(controller.compute(controller.context(editor)))
        assertEquals(SuggestionChip("the", ChipStyle.Normal), chips[1])
        controller.onSeparator(editor, " ")
        assertEquals("the ", editor.toString())
    }

    @Test
    fun swipedWordsGetSpacedAutomatically() {
        controller.onSwipe(editor, listOf("hello", "help"))
        assertEquals("hello", editor.toString())
        controller.onSwipe(editor, listOf("there"))
        assertEquals("hello there", editor.toString())
        controller.onText(editor, "x") // tapping after a swipe starts a new word
        assertEquals("hello there x", editor.toString())
    }

    @Test
    fun punctuationAttachesToSwipedWord() {
        controller.onSwipe(editor, listOf("hello"))
        controller.onSeparator(editor, ".")
        assertEquals("hello.", editor.toString())
    }

    @Test
    fun deleteRemovesWholeSwipedWord() {
        editor.insert("I said")
        controller.onSwipe(editor, listOf("hello"))
        assertEquals("I said hello", editor.toString())
        assertTrue(controller.onBackspace(editor))
        assertEquals("I said ", editor.toString())
    }

    @Test
    fun stripOffersOtherGuessesAndSwaps() {
        controller.onSwipe(editor, listOf("for", "four", "fir"))
        val chips = controller.publish(controller.compute(controller.context(editor)))
        assertEquals(listOf("four", "for", "fir"), chips.map { it?.text })
        controller.onPick(editor, chips[0]!!)
        assertEquals("four", editor.toString())
        controller.onSwipe(editor, listOf("seasons"))
        assertEquals("four seasons", editor.toString())
    }

    @Test
    fun respectsAutocorrectSetting() {
        controller.autocorrectEnabled = false
        type("teh")
        controller.onSeparator(editor, " ")
        assertEquals("teh ", editor.toString())
    }

    @Test
    fun learnsTypedWordsAndVerbatimPicks() {
        repeat(2) {
            type("Zorblax")
            controller.onSeparator(editor, " ")
        }
        assertTrue(controller.suggester!!.isKnown("Zorblax"))

        type("Quuxly")
        controller.onPick(editor, SuggestionChip("Quuxly", ChipStyle.Verbatim))
        assertTrue(controller.suggester!!.isKnown("Quuxly"))
    }

    @Test
    fun doesNotLearnWhenDisabled() {
        controller.learningEnabled = false
        repeat(3) {
            type("Zorblax")
            controller.onSeparator(editor, " ")
        }
        assertFalse(controller.suggester!!.isKnown("Zorblax"))
    }

    @Test
    fun chipsShowVerbatimAutocorrectAndAlternative() {
        type("teh")
        val chips = controller.publish(controller.compute(controller.context(editor)))
        assertEquals(SuggestionChip("teh", ChipStyle.Verbatim), chips[0])
        assertEquals(SuggestionChip("the", ChipStyle.Autocorrect), chips[1])
    }

    @Test
    fun chipsPutBestPredictionInCenter() {
        editor.insert("I ")
        val chips = controller.publish(controller.compute(controller.context(editor)))
        assertEquals("am", chips[1]?.text)
    }

    @Test
    fun finishingWordForEditorActionAddsNothing() {
        type("teh")
        controller.onSeparator(editor, "")
        assertEquals("the", editor.toString())
    }

    // --- Learning from the user's typing -----------------------------------------------------

    @Test
    fun finishedWordsAreLearnedAsPairs() {
        type("I")
        controller.onSeparator(editor, " ")
        type("know")
        controller.onSeparator(editor, " ")
        assertTrue(learned.pairBoost("I", "know") > 0f)
        assertTrue(learned.boost("know") > 0f)
    }

    @Test
    fun undoingACorrectionIsRememberedForGood() {
        type("teh")
        controller.onSeparator(editor, " ")
        assertTrue(controller.onBackspace(editor))
        assertTrue(learned.isKeptAsTyped("teh"))
        assertEquals(0f, learned.boost("the"), 0f) // the correction was taken back
        assertTrue(learned.boost("teh") > 0f)

        // A new session (keyboard restarted) still leaves it alone.
        val fresh = TypingController(learned).apply { suggester = controller.suggester }
        val e = FakeEditor()
        "teh".forEach { fresh.onText(e, it.toString()) }
        fresh.onSeparator(e, " ")
        assertEquals("teh ", e.toString())
    }

    @Test
    fun swipedWordIsLearnedOnceTheUserMovesOn() {
        controller.onSwipe(editor, listOf("for", "four"))
        assertEquals(0f, learned.boost("for"), 0f) // might still be swapped or deleted
        controller.onPick(editor, SuggestionChip("four"))
        controller.onSwipe(editor, listOf("you"))
        assertTrue(learned.boost("four") > 0f)
        assertEquals(0f, learned.boost("for"), 0f)
        controller.onSeparator(editor, " ")
        assertTrue(learned.pairBoost("four", "you") > 0f)
    }

    @Test
    fun deletedSwipeIsNotLearned() {
        controller.onSwipe(editor, listOf("hello"))
        controller.onBackspace(editor)
        controller.onSwipe(editor, listOf("help"))
        assertEquals(0f, learned.boost("hello"), 0f)
    }

    @Test
    fun picksAreLearnedAfterThePreviousWord() {
        type("I")
        controller.onSeparator(editor, " ")
        controller.onPick(editor, SuggestionChip("think"))
        assertTrue(learned.pairBoost("I", "think") > 0f)
    }

    @Test
    fun nothingIsLearnedWhenLearningIsOff() {
        controller.learningEnabled = false
        type("I")
        controller.onSeparator(editor, " ")
        type("teh")
        controller.onSeparator(editor, " ")
        controller.onBackspace(editor)
        assertEquals(0f, learned.boost("I"), 0f)
        assertFalse(learned.isKeptAsTyped("teh"))
    }

    @Test
    fun removingASuggestionStopsIt() {
        type("teh")
        controller.onRemove("the")
        controller.onSeparator(editor, " ")
        assertFalse(editor.toString() == "the ")
    }
}
