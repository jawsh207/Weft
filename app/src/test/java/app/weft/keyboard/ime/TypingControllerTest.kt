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

/**
 * Types into a fake text field and checks the word-level behavior: autocorrect on space and its
 * undo on backspace, picking suggestions, spacing around punctuation, the capital "I", swiped
 * words, emoji suggestions, and what gets learned or forgotten.
 */
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
        assertEquals("hello ", editor.toString()) // the space goes in as the finger lifts
        controller.onSwipe(editor, listOf("there"))
        assertEquals("hello there ", editor.toString())
        controller.onText(editor, "x")
        assertEquals("hello there x", editor.toString())
        val e = FakeEditor().apply { insert("I said") }
        controller.onSwipe(e, listOf("hello"))
        assertEquals("I said hello ", e.toString())
    }

    @Test
    fun stripPredictsTheNextWordRightAfterASwipe() {
        controller.onSwipe(editor, listOf("for", "four"))
        val ctx = controller.context(editor)
        assertEquals("", ctx.fragment)
        assertEquals("for", ctx.previous) // predictions follow the swiped word
        val chips = controller.publish(controller.compute(ctx))
        assertFalse(chips.any { it?.text == "four" }) // not the swipe's other guesses
    }

    @Test
    fun spaceAfterASwipeIsNotDoubled() {
        controller.onSwipe(editor, listOf("hello"))
        controller.onSeparator(editor, " ")
        assertEquals("hello ", editor.toString())
    }

    @Test
    fun punctuationAttachesToSwipedWord() {
        controller.onSwipe(editor, listOf("hello"))
        controller.onSeparator(editor, ".")
        assertEquals("hello. ", editor.toString())
    }

    @Test
    fun backspaceBringsBackGuessesThenDeletesWholeSwipedWord() {
        editor.insert("I said")
        controller.onSwipe(editor, listOf("hello", "help", "hell"))
        assertEquals("I said hello ", editor.toString())
        assertTrue(controller.onBackspace(editor))
        assertEquals("I said hello", editor.toString())
        val chips = controller.publish(controller.compute(controller.context(editor)))
        assertEquals(listOf("help", "hello", "hell"), chips.map { it?.text })
        assertTrue(controller.onBackspace(editor))
        assertEquals("I said ", editor.toString())
    }

    @Test
    fun stripOffersOtherGuessesAndSwaps() {
        controller.onSwipe(editor, listOf("for", "four", "fir"))
        controller.onBackspace(editor)
        val chips = controller.publish(controller.compute(controller.context(editor)))
        assertEquals(listOf("four", "for", "fir"), chips.map { it?.text })
        controller.onPick(editor, chips[0]!!)
        assertEquals("four ", editor.toString()) // the space comes back with the swap
        controller.onSwipe(editor, listOf("seasons"))
        assertEquals("four seasons ", editor.toString())
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
        controller.onBackspace(editor)
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

    // --- Emoji suggestions -----------------------------------------------------------------------

    private fun withEmoji() {
        controller.emojiFor = { w -> mapOf("hello" to "👋", "keyboard" to "⌨️")[w.lowercase()] }
    }

    @Test
    fun offersEmojiForTheWordBeingTyped() {
        withEmoji()
        type("hello")
        val chips = controller.publish(controller.compute(controller.context(editor)))
        assertEquals(SuggestionChip("👋", ChipStyle.Emoji), chips[2])
        assertEquals("hello", chips[1]?.text) // space still keeps the word
        controller.onPick(editor, chips[2]!!)
        assertEquals("hello 👋 ", editor.toString())
    }

    @Test
    fun emojiFollowsTheWordSpaceWouldMake() {
        withEmoji()
        type("keyb")
        val chips = controller.publish(controller.compute(controller.context(editor)))
        assertEquals("⌨️", chips[2]?.text)
        controller.onPick(editor, chips[2]!!)
        assertEquals("keyboard ⌨️ ", editor.toString())
    }

    @Test
    fun emojiForTheWordJustFinished() {
        withEmoji()
        type("hello")
        controller.onSeparator(editor, " ")
        val chips = controller.publish(controller.compute(controller.context(editor)))
        assertEquals("👋", chips.getOrNull(2)?.text)
        controller.onPick(editor, chips[2]!!)
        assertEquals("hello 👋 ", editor.toString())
    }

    @Test
    fun noEmojiWithoutAMatchOrWhenOff() {
        withEmoji()
        type("the")
        assertFalse(controller.publish(controller.compute(controller.context(editor))).any { it?.style == ChipStyle.Emoji })
        controller.emojiFor = null
        type("hello")
        assertFalse(controller.publish(controller.compute(controller.context(editor))).any { it?.style == ChipStyle.Emoji })
    }

    // --- "i" → "I" -----------------------------------------------------------------------------

    @Test
    fun capitalizesIEvenWithAutocorrectOff() {
        controller.autocorrectEnabled = false
        type("i")
        controller.onSeparator(editor, " ")
        type("i'm")
        controller.onSeparator(editor, " ")
        assertEquals("I I'm ", editor.toString())
    }

    @Test
    fun undoingIOnlyCountsOnce() {
        type("i")
        controller.onSeparator(editor, " ")
        assertTrue(controller.onBackspace(editor))
        assertEquals("i ", editor.toString())
        assertFalse(learned.isKeptAsTyped("i"))
        type("i")
        controller.onSeparator(editor, " ")
        assertEquals("i I ", editor.toString())
    }

    @Test
    fun capitalizesIEvenIfItWasKeptAsTypedBefore() {
        learned.keepAsTyped("i") // from earlier versions, which remembered an undo
        type("i")
        controller.onSeparator(editor, " ")
        assertEquals("I ", editor.toString())
    }

    @Test
    fun stripShowsCapitalIInTheMiddle() {
        controller.autocorrectEnabled = false
        type("i")
        val chips = controller.publish(controller.compute(controller.context(editor)))
        assertEquals(SuggestionChip("I", ChipStyle.Autocorrect), chips[1])
    }

    @Test
    fun shiftCapitalizesSuggestions() {
        val chip = SuggestionChip("the")
        assertEquals(chip, TypingController.withShift(chip, on = false, locked = false))
        assertEquals(SuggestionChip("The"), TypingController.withShift(chip, on = true, locked = false))
        assertEquals(SuggestionChip("THE"), TypingController.withShift(chip, on = true, locked = true))
        val asTyped = SuggestionChip("teh", ChipStyle.Verbatim)
        assertEquals(asTyped, TypingController.withShift(asTyped, on = true, locked = false))
        val emoji = SuggestionChip("😀", ChipStyle.Emoji)
        assertEquals(emoji, TypingController.withShift(emoji, on = true, locked = true))
    }

    @Test
    fun leavesIAloneWhereCapitalizingIsOff() {
        controller.capitalizeI = false
        controller.autocorrectEnabled = false
        type("i")
        controller.onSeparator(editor, " ")
        assertEquals("i ", editor.toString())
        assertEquals(null, TypingController.capitalI("id"))
        assertEquals("I'd", TypingController.capitalI("i'd"))
        assertEquals(null, TypingController.capitalI("I"))
    }

    // --- Learning from swipes -----------------------------------------------------------------------

    private val kept = ArrayList<Pair<String, String>>()

    private fun watchSwipes() {
        controller.onSwipeKept = { k, first, _, _ -> kept += k to first }
    }

    private val pts = floatArrayOf(0f, 1f) to floatArrayOf(0f, 0f)

    @Test
    fun aSwipeIsLearnedFromOnceTheUserMovesOn() {
        watchSwipes()
        controller.onSwipe(editor, listOf("for", "four"), pts.first, pts.second)
        assertTrue(kept.isEmpty()) // could still be swapped or deleted
        controller.onSeparator(editor, " ")
        assertEquals(listOf("for" to "for"), kept)
    }

    @Test
    fun aSwappedSwipeTeachesThePick() {
        watchSwipes()
        controller.onSwipe(editor, listOf("for", "four"), pts.first, pts.second)
        controller.onBackspace(editor)
        controller.onPick(editor, SuggestionChip("four"))
        controller.onSwipe(editor, listOf("you"), pts.first, pts.second)
        assertEquals(listOf("four" to "for"), kept)
    }

    @Test
    fun aDeletedSwipeTeachesNothing() {
        watchSwipes()
        controller.onSwipe(editor, listOf("for"), pts.first, pts.second)
        controller.onBackspace(editor)
        controller.onBackspace(editor)
        controller.onSwipe(editor, listOf("you"), pts.first, pts.second)
        assertEquals(emptyList<Pair<String, String>>(), kept.dropLast(0).filter { it.first == "for" })
    }

    @Test
    fun noSwipeLearningWhenLearningIsOff() {
        watchSwipes()
        controller.learningEnabled = false
        controller.onSwipe(editor, listOf("for"), pts.first, pts.second)
        controller.onSeparator(editor, " ")
        assertTrue(kept.isEmpty())
    }
}
