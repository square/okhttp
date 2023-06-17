package okhttp3.android

import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionListener
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import okio.IOException

class LoggingConnectionListener(
  private val logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT
): ConnectionListener() {
  override fun connectStart(route: Route, call: Call) {
    logger.log("connectStart($route, $call)")
  }

  override fun connectFailed(route: Route, call: Call, failure: IOException) {
    logger.log("connectStart($route, $call, $failure)")
  }

  override fun connectEnd(connection: Connection, route: Route, call: Call) {
    logger.log("connectStart($connection, $route, $call)")
  }

  override fun connectionClosed(connection: Connection) {
    logger.log("connectStart($connection)")
  }

  override fun connectionAcquired(connection: Connection, call: Call) {
    logger.log("connectionAcquired($connection, $call)")
  }

  override fun connectionReleased(connection: Connection, call: Call) {
    logger.log("connectionReleased()")
  }

  override fun noNewExchanges(connection: Connection) {
    logger.log("noNewExchanges()")
  }
}
