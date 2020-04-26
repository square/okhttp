package okhttp3.internal.platform

import okhttp3.internal.platform.Logger.Level
import okhttp3.internal.platform.Logger.Level.DEBUG
import okhttp3.internal.platform.Logger.Level.INFO
import okhttp3.internal.platform.Logger.Level.WARN

private val Level.toJul: java.util.logging.Level
  get() = when (this) {
    DEBUG -> java.util.logging.Level.FINE
    INFO -> java.util.logging.Level.INFO
    WARN -> java.util.logging.Level.WARNING
  }

class JulLogger(val logger: java.util.logging.Logger) : Logger {
  override fun debug(message: String, e: Throwable?) = logger.log(java.util.logging.Level.FINE, message, e)
  override fun info(message: String, e: Throwable?) = logger.log(java.util.logging.Level.INFO, message, e)
  override fun warn(message: String, e: Throwable?) = logger.log(java.util.logging.Level.WARNING, message, e)
  override fun isLoggable(level: Level): Boolean = logger.isLoggable(level.toJul)
}
