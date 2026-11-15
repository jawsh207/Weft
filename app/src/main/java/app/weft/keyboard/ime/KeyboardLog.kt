package app.weft.keyboard.ime

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short diary of what the keyboard was asked to do and what it did, for
 * tracking down problems that only happen now and then ("the keyboard didn't
 * open"). Settings › About › Troubleshooting lets the user copy it.
 *
 * It records things like "show requested by app X, field type Y", "window
 * shown, 1080x820", "the app took 2,000 ms to answer" and crashes. It never
 * records what is typed, and it never leaves the phone unless the user copies
 * and shares it: Weft has no network access.
 *
 * Only the most recent [MAX_EVENTS] lines are kept. They live in memory (the
 * keyboard and the settings screen share one app process) and are saved to a
 * small file so they survive the keyboard being restarted, which is itself one
 * of the things worth seeing in the log.
 */
object KeyboardLog {
    private const val MAX_EVENTS = 300

    /** A crash's stack trace is cut to this many lines: enough to find the cause. */
    private const val MAX_TRACE_LINES = 30

    private val events = ArrayDeque<String>()
    private var file: File? = null
    private val clock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Where the log is saved; reads back what an earlier run of the keyboard left there. */
    @Synchronized
    fun attach(dir: File) {
        if (file != null) return
        val f = File(dir, "keyboard_log.txt")
        file = f
        val earlier = runCatching { f.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        // Lines noted before attaching (there shouldn't be any) stay after the saved ones.
        val fresh = events.toList()
        events.clear()
        (earlier + fresh).takeLast(MAX_EVENTS).forEach(events::addLast)
    }

    /** Adds one line, stamped with the time. Cheap enough to call from the main thread. */
    @Synchronized
    fun note(event: String, now: Long = System.currentTimeMillis()) {
        // Continuation lines (a stack trace) are indented so the file reads back one event per line group.
        events.addLast(clock.format(Date(now)) + "  " + event.replace("\n", "\n    "))
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    /** Writes the log to its file. Call off the main thread, except when crashing. */
    @Synchronized
    fun save() {
        val f = file ?: return
        runCatching { f.writeText(events.joinToString("\n") + "\n") }
    }

    /** Everything recorded, oldest first. */
    @Synchronized
    fun text(): String = events.joinToString("\n")

    /** Whether nothing has been recorded. */
    @Synchronized
    fun isEmpty(): Boolean = events.isEmpty()

    /** Forgets everything, in memory and on disk. */
    @Synchronized
    fun clear() {
        events.clear()
        runCatching { file?.delete() }
    }

    /**
     * Records a crash before the app dies, then lets Android handle it as usual.
     * Without this, a keyboard that crashes just disappears and nobody knows why.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashRecorder) return
        Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(previous))
    }

    /** Writes the crash into the log, saves it, then hands over to [next] (Android's own handler). */
    private class CrashRecorder(private val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                note("CRASH on thread ${thread.name}\n" + trace(error))
                save()
            }
            next?.uncaughtException(thread, error)
        }
    }

    /** The first lines of [error]'s stack trace. */
    internal fun trace(error: Throwable): String {
        val out = StringWriter()
        error.printStackTrace(PrintWriter(out))
        val lines = out.toString().trimEnd().lines()
        return if (lines.size <= MAX_TRACE_LINES) lines.joinToString("\n")
        else lines.take(MAX_TRACE_LINES).joinToString("\n") + "\n… ${lines.size - MAX_TRACE_LINES} more lines"
    }
}
