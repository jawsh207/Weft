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
}
