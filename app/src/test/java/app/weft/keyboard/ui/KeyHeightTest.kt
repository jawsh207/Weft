package app.weft.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the key height setting: four heights from Mini to Tall with Normal
 * as the default, and that a choice saved under the old three names keeps the
 * same physical size.
 */
class KeyHeightTest {
    @Test
    fun fourHeightsInIncreasingOrder() {
        assertEquals(listOf("mini", "compact", "normal", "tall"), KeyHeight.entries.map { it.key })
        val rows = KeyHeight.entries.map { it.row.value }
        assertEquals(rows.sorted(), rows)
        assertEquals(4, rows.distinct().size)
    }

    @Test
    fun normalIsTheDefault() {
        assertEquals(KeyHeight.Normal, KeyHeight.of(null))
        assertEquals(KeyHeight.Normal, KeyHeight.of("something else", null))
    }

    @Test
    fun aSavedChoiceIsReadBack() {
        for (height in KeyHeight.entries) assertEquals(height, KeyHeight.of(height.key))
    }

    @Test
    fun anOldChoiceKeepsItsSize() {
        // What v1.0.0 called compact / normal / tall were 50, 58 and 66 dp.
        assertEquals(50f, KeyHeight.of(null, "compact").row.value, 0f)
        assertEquals(58f, KeyHeight.of(null, "normal").row.value, 0f)
        assertEquals(66f, KeyHeight.of(null, "tall").row.value, 0f)
    }

    @Test
    fun aNewChoiceWinsOverAnOldOne() {
        assertEquals(KeyHeight.Tall, KeyHeight.of("tall", "compact"))
        assertTrue(KeyHeight.of("mini", "tall") == KeyHeight.Mini)
    }
}
