package app.weft.keyboard.ime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The troubleshooting log: keeps only recent lines, survives a restart through
 * its file, shortens long stack traces and can be cleared.
 *
 * [KeyboardLog] is one shared object, so each test detaches it from its file
 * afterwards (by reflection: the app never needs to).
 */
class KeyboardLogTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        detach()
        dir = Files.createTempDirectory("weftlog").toFile()
    }

    @After
    fun tearDown() {
        detach()
        dir.deleteRecursively()
    }

    private fun detach() {
        KeyboardLog.clear()
        KeyboardLog::class.java.getDeclaredField("file").apply { isAccessible = true }.set(KeyboardLog, null)
    }

    @Test
    fun keepsOnlyTheMostRecentLines() {
        repeat(350) { KeyboardLog.note("event $it") }
        val lines = KeyboardLog.text().lines()
        assertEquals(300, lines.size)
        assertTrue(lines.first().endsWith("event 50"))
        assertTrue(lines.last().endsWith("event 349"))
    }

    @Test
    fun whatWasSavedComesBackAfterARestart() {
        KeyboardLog.attach(dir)
        KeyboardLog.note("window shown")
        KeyboardLog.save()
        detach() // the keyboard process ended…
        File(dir, "keyboard_log.txt").writeText("11-15 00:00:00.000  window shown\n")
        KeyboardLog.attach(dir) // …and started again
        KeyboardLog.note("keyboard service started")
        val lines = KeyboardLog.text().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("window shown"))
        assertTrue(lines[1].endsWith("keyboard service started"))
    }

    @Test
    fun clearingForgetsTheFileToo() {
        KeyboardLog.attach(dir)
        KeyboardLog.note("something")
        KeyboardLog.save()
        KeyboardLog.clear()
        assertTrue(KeyboardLog.isEmpty())
        assertTrue(!File(dir, "keyboard_log.txt").exists())
    }

    @Test
    fun longStackTracesAreCutShort() {
        fun deep(n: Int): Nothing = if (n == 0) throw IllegalStateException("boom") else deep(n - 1)
        val trace = runCatching { deep(200) }.exceptionOrNull()!!.let(KeyboardLog::trace)
        assertTrue(trace.startsWith("java.lang.IllegalStateException: boom"))
        assertTrue(trace.lines().size <= 31)
        assertTrue(trace.lines().last().contains("more lines"))
    }
}
