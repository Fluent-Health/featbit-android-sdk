package co.featbit.client

/** Severity levels used by [FBLogger]. */
public enum class LogLevel { DEBUG, INFO, WARN, ERROR, NONE }

/**
 * Minimal logging facade for the SDK, replacing the .NET SDK's `ILoggerFactory`.
 *
 * Provide your own implementation via [co.featbit.client.options.FBOptions.Builder.logger]
 * to forward SDK logs into your app's logging pipeline. The [debug] message is supplied as a
 * lambda so that no string is built when debug logging is disabled.
 */
public interface FBLogger {
    public fun debug(message: () -> String)
    public fun info(message: String)
    public fun warn(message: String)
    public fun error(message: String, throwable: Throwable? = null)

    public companion object {
        /** A logger that discards all messages. */
        public val NoOp: FBLogger = object : FBLogger {
            override fun debug(message: () -> String) {}
            override fun info(message: String) {}
            override fun warn(message: String) {}
            override fun error(message: String, throwable: Throwable?) {}
        }
    }
}

/**
 * Default [FBLogger] that writes to Android's logcat when available, falling back to
 * standard streams on plain JVM (e.g. unit tests). Messages below [minLevel] are dropped.
 */
public class DefaultLogger(
    private val tag: String = "FeatBit",
    private val minLevel: LogLevel = LogLevel.INFO,
) : FBLogger {

    private fun enabled(level: LogLevel): Boolean = level.ordinal >= minLevel.ordinal

    override fun debug(message: () -> String) {
        if (enabled(LogLevel.DEBUG)) write(LogLevel.DEBUG, message(), null)
    }

    override fun info(message: String) {
        if (enabled(LogLevel.INFO)) write(LogLevel.INFO, message, null)
    }

    override fun warn(message: String) {
        if (enabled(LogLevel.WARN)) write(LogLevel.WARN, message, null)
    }

    override fun error(message: String, throwable: Throwable?) {
        if (enabled(LogLevel.ERROR)) write(LogLevel.ERROR, message, throwable)
    }

    private fun write(level: LogLevel, message: String, throwable: Throwable?) {
        try {
            when (level) {
                LogLevel.DEBUG -> android.util.Log.d(tag, message, throwable)
                LogLevel.INFO -> android.util.Log.i(tag, message, throwable)
                LogLevel.WARN -> android.util.Log.w(tag, message, throwable)
                LogLevel.ERROR -> android.util.Log.e(tag, message, throwable)
                LogLevel.NONE -> {}
            }
        } catch (_: Throwable) {
            // android.util.Log is unavailable (e.g. local JVM unit tests) — fall back.
            val stream = if (level == LogLevel.ERROR || level == LogLevel.WARN) System.err else System.out
            stream.println("[$tag] $level: $message")
            throwable?.printStackTrace(stream)
        }
    }
}
