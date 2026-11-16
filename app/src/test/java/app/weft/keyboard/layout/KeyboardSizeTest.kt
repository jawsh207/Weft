package app.weft.keyboard.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the keyboard's height: the user's choice in portrait, about half the
 * screen on a phone held sideways, and unchanged on screens tall enough not to
 * need it.
 */
class KeyboardSizeTest {
    private val normal = 58f

    @Test
    fun portraitUsesTheChosenHeight() {
        assertEquals(normal, KeyboardSize.rowHeight(normal, 800, landscape = false, numberRow = false), 0f)
        assertEquals(66f, KeyboardSize.rowHeight(66f, 640, landscape = false, numberRow = true), 0f)
    }

    @Test
    fun aPhoneOnItsSideGetsShorterRows() {
        for (screen in listOf(360, 390, 412)) {
            val row = KeyboardSize.rowHeight(normal, screen, landscape = true, numberRow = false)
            assertTrue("rows should shrink on a $screen dp tall screen", row < normal)
            assertTrue("rows stay big enough to hit", row >= KeyboardSize.MIN_ROW_DP)
            val share = KeyboardSize.totalHeight(row, numberRow = false) / screen
            assertTrue("keyboard takes $share of a $screen dp tall screen", share <= 0.6f)
        }
    }

    @Test
    fun theNumberRowIsCountedToo() {
        val without = KeyboardSize.rowHeight(normal, 480, landscape = true, numberRow = false)
        val with = KeyboardSize.rowHeight(normal, 480, landscape = true, numberRow = true)
        assertTrue(with < without)
    }

    @Test
    fun tallScreensKeepTheChosenHeightInLandscape() {
        assertEquals(normal, KeyboardSize.rowHeight(normal, 800, landscape = true, numberRow = true), 0f)
    }

    @Test
    fun rowsNeverGrowBeyondTheChoiceOrShrinkBelowTheMinimum() {
        // A compact choice smaller than the minimum is kept as it is.
        assertEquals(30f, KeyboardSize.rowHeight(30f, 300, landscape = true, numberRow = false), 0f)
        assertEquals(KeyboardSize.MIN_ROW_DP, KeyboardSize.rowHeight(normal, 200, landscape = true, numberRow = true), 0f)
        assertEquals(50f, KeyboardSize.rowHeight(50f, 2000, landscape = true, numberRow = false), 0f)
    }
}
