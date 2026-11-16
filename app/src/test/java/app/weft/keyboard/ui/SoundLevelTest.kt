package app.weft.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the key sound setting: off unless chosen, a saved choice is read
 * back, and each level is louder than the one before.
 */
class SoundLevelTest {
    @Test
    fun offIsTheDefault() {
        assertEquals(SoundLevel.Off, SoundLevel.of(null))
        assertEquals(SoundLevel.Off, SoundLevel.of("something else"))
        assertEquals(0f, SoundLevel.Off.volume, 0f)
    }

    @Test
    fun aSavedChoiceIsReadBack() {
        for (level in SoundLevel.entries) assertEquals(level, SoundLevel.of(level.key))
    }

    @Test
    fun eachLevelIsLouderThanTheLast() {
        val volumes = SoundLevel.entries.map { it.volume }
        assertEquals(volumes.sorted(), volumes)
        assertEquals(volumes.size, volumes.distinct().size)
        assertTrue(volumes.all { it in 0f..1f })
    }
}
