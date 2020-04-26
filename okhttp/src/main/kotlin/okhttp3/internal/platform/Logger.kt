package okhttp3.internal.platform

interface Logger {
  fun debug(message: String, e: Throwable? = null)
  fun info(message: String, e: Throwable? = null)
  fun warn(message: String, e: Throwable? = null)
  fun isLoggable(debug: Level): Boolean

  enum class Level {
    DEBUG,
    INFO,
    WARN
  }
}
