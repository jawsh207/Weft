package app.weft.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPolicyTest {

    private val plain = FieldTraits(
        isText = true, isPassword = false, noSuggestions = false, technical = false, noLearning = false,
    )
    private val allOn = TypingPrefs(suggestions = true, autocorrect = true, learn = true, swipe = true)

    @Test
    fun plainTextFieldGetsEverything() {
        assertEquals(FieldPolicy(true, true, true, true), FieldPolicy.of(plain, allOn))
    }

    @Test
    fun noSuggestionsFlagKeepsTheStripButNeverAutocorrects() {
        val p = FieldPolicy.of(plain.copy(noSuggestions = true), allOn)
        assertTrue("tap typing still gets suggestions, like swipe", p.suggestions)
        assertTrue(p.swipe)
        assertFalse(p.autocorrect)
    }

    @Test
    fun tapAndSwipeAgreeInEveryOrdinaryTextField() {
        for (noSuggestions in listOf(false, true)) for (noLearning in listOf(false, true)) {
            val p = FieldPolicy.of(plain.copy(noSuggestions = noSuggestions, noLearning = noLearning), allOn)
            assertEquals(p.swipe, p.suggestions)
        }
    }

    @Test
    fun passwordsGetNothing() {
        val p = FieldPolicy.of(plain.copy(isPassword = true), allOn)
        assertEquals(FieldPolicy(false, false, false, false), p)
    }

    @Test
    fun technicalFieldsSuggestButDontCorrectOrSwipe() {
        val p = FieldPolicy.of(plain.copy(technical = true), allOn)
        assertTrue(p.suggestions)
        assertFalse(p.autocorrect)
        assertFalse(p.swipe)
    }

    @Test
    fun settingsAreRespected() {
        val p = FieldPolicy.of(plain, allOn.copy(suggestions = false, swipe = false, learn = false))
        assertEquals(FieldPolicy(false, false, false, false), p)
        assertFalse(FieldPolicy.of(plain, allOn.copy(autocorrect = false)).autocorrect)
        assertFalse(FieldPolicy.of(plain.copy(noLearning = true), allOn).learning)
    }

    @Test
    fun nonTextFieldsGetNothingButLearning() {
        val p = FieldPolicy.of(plain.copy(isText = false), allOn)
        assertFalse(p.suggestions || p.autocorrect || p.swipe)
    }
}
