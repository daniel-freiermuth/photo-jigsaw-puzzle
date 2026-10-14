package android.util

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Logcat for JVM unit tests: android.jar's Log only has stubs that throw, so this writes to stderr instead.
 * Test classes come before android.jar on the test classpath, so the app's and libraries' calls (OkHttp
 * probes isLoggable) resolve here. Mirrors the public android.util.Log API; like on a device, only INFO and
 * above are loggable by default.
 */
@Suppress("TooManyFunctions") // The whole android.util.Log API, so no library call misses.
object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    const val ASSERT = 7

    private const val LEVELS = "??VDIWEA"

    @JvmStatic fun v(tag: String?, msg: String?): Int = write(VERBOSE, tag, msg)

    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = write(VERBOSE, tag, withTrace(msg, tr))

    @JvmStatic fun d(tag: String?, msg: String?): Int = write(DEBUG, tag, msg)

    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = write(DEBUG, tag, withTrace(msg, tr))

    @JvmStatic fun i(tag: String?, msg: String?): Int = write(INFO, tag, msg)

    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = write(INFO, tag, withTrace(msg, tr))

    @JvmStatic fun w(tag: String?, msg: String?): Int = write(WARN, tag, msg)

    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = write(WARN, tag, withTrace(msg, tr))

    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = write(WARN, tag, getStackTraceString(tr))

    @JvmStatic fun e(tag: String?, msg: String?): Int = write(ERROR, tag, msg)

    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = write(ERROR, tag, withTrace(msg, tr))

    @JvmStatic fun wtf(tag: String?, msg: String?): Int = write(ASSERT, tag, msg)

    @JvmStatic fun wtf(tag: String?, tr: Throwable?): Int = write(ASSERT, tag, getStackTraceString(tr))

    @JvmStatic fun wtf(tag: String?, msg: String?, tr: Throwable?): Int = write(ASSERT, tag, withTrace(msg, tr))

    @Suppress("UnusedParameter") // Same signature as the platform's; the level alone decides, as on a device.
    @JvmStatic
    fun isLoggable(tag: String?, level: Int): Boolean = level >= INFO

    @JvmStatic fun getStackTraceString(tr: Throwable?): String {
        if (tr == null) return ""
        val out = StringWriter()
        PrintWriter(out).use { tr.printStackTrace(it) }
        return out.toString()
    }

    @JvmStatic fun println(priority: Int, tag: String?, msg: String?): Int = write(priority, tag, msg)

    /** Not named println: lint would take the calls above for unconditional Log.println calls. */
    private fun write(priority: Int, tag: String?, msg: String?): Int {
        val line = "${LEVELS.getOrElse(priority) { '?' }}/$tag: $msg"
        System.err.println(line)
        return line.length
    }

    private fun withTrace(msg: String?, tr: Throwable?) = "$msg\n${getStackTraceString(tr)}"
}
