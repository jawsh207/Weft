package app.weft.keyboard.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {

    private fun widths(rows: List<KeyRow>) = rows.map { row -> row.sumOf { it.weight.toDouble() } }

    @Test
    fun rowsOfEachPageAreTheSameWidth() {
        for ((name, rows) in mapOf(
            "letters" to Layouts.qwerty,
            "symbols" to Layouts.symbols,
            "moreSymbols" to Layouts.moreSymbols,
            "numpad" to Layouts.numpad,
        )) {
            val w = widths(rows)
            assertTrue("$name rows differ: $w", w.all { kotlin.math.abs(it - w[0]) < 0.001 })
        }
    }

    @Test
    fun symbolPagesHaveNumpadKeyInsteadOfEmoji() {
        for (rows in listOf(Layouts.symbols, Layouts.moreSymbols)) {
            val keys = rows.flatten()
            assertTrue(keys.contains(ActionKey(KeyAction.ToNumpad)))
            assertFalse(keys.any { it is ActionKey && it.action == KeyAction.Emoji })
        }
        assertTrue(Layouts.qwerty.flatten().any { it is ActionKey && it.action == KeyAction.Emoji })
    }

    @Test
    fun numpadHasAllDigitsAndWayBack() {
        val outputs = Layouts.numpad.flatten().filterIsInstance<CharKey>().map { it.output }
        assertTrue(outputs.containsAll(('0'..'9').map { it.toString() }))
        val actions = Layouts.numpad.flatten().filterIsInstance<ActionKey>().map { it.action }
        assertTrue(actions.containsAll(listOf(KeyAction.ToLetters, KeyAction.ToSymbols, KeyAction.Backspace, KeyAction.Enter)))
    }

    @Test
    fun digitsOfferEveryFractionUsingThem() {
        // All Unicode vulgar fractions, as numerator/denominator.
        val fractions = mapOf(
            "½" to "1/2", "⅓" to "1/3", "⅔" to "2/3", "¼" to "1/4", "¾" to "3/4",
            "⅕" to "1/5", "⅖" to "2/5", "⅗" to "3/5", "⅘" to "4/5", "⅙" to "1/6",
            "⅚" to "5/6", "⅐" to "1/7", "⅛" to "1/8", "⅜" to "3/8", "⅝" to "5/8",
            "⅞" to "7/8", "⅑" to "1/9", "⅒" to "1/10", "↉" to "0/3",
        )
        for (digit in '0'..'9') {
            val expected = fractions.filterValues { digit in it }.keys
            val actual = Layouts.alternatesFor(digit.toString()).filter { it in fractions }.toSet()
            assertEquals("fractions for $digit", expected, actual)
        }
    }

    @Test
    fun longPressAlternates() {
        val e = Layouts.alternatesFor("e")
        assertEquals("3", e.first())
        assertTrue(e.containsAll(listOf("è", "é", "ê", "ë", "ē")))
        assertEquals(e, Layouts.alternatesFor("E"))
        assertTrue(Layouts.alternatesFor("x").isEmpty())
        assertEquals("…", Layouts.alternatesFor(".").last())
        // Every alternate is a single visible character and never repeats its key.
        for (key in "abcdefghijklmnopqrstuvwxyz.,?!$-'\"%*&+=01234") {
            val alts = Layouts.alternatesFor(key.toString())
            assertEquals(alts.size, alts.toSet().size)
            assertFalse(alts.contains(key.toString()))
        }
    }
}
