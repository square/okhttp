package okhttp3.android.tracing

import androidx.tracing.Trace
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Route
import okhttp3.internal.connection.ConnectionListener
import okio.IOException

/**
 * Tracing implementation of ConnectionListener that marks the lifetime of each connection
 * in Perfetto traces.
 */
class AndroidxTracingConnectionListener(
    private val delegate: ConnectionListener = NONE,
    val traceLabel: (Route) -> String = { it.defaultTracingLabel },
) : ConnectionListener() {
  override fun connectStart(
      connectionId: Long,
      route: Route,
      call: Call,
  ) {
    Trace.beginAsyncSection(labelForTrace(route), connectionId.toInt())
    delegate.connectStart(connectionId, route, call)
  }

  override fun connectFailed(
      connectionId: Long,
      route: Route,
      call: Call,
      failure: IOException,
  ) {
    Trace.endAsyncSection(labelForTrace(route), connectionId.toInt())
    delegate.connectFailed(connectionId, route, call, failure)
  }

  override fun connectEnd(
      connection: Connection,
      route: Route,
      call: Call,
  ) {
    delegate.connectEnd(connection, route, call)
  }

  override fun connectionClosed(connection: Connection) {
    Trace.endAsyncSection(labelForTrace(connection.route()), connection.id.toInt())
    delegate.connectionClosed(connection)
  }

  private fun labelForTrace(route: Route): String = traceLabel(route).take(AndroidxTracingInterceptor.Companion.MAX_TRACE_LABEL_LENGTH)

  override fun connectionAcquired(
      connection: Connection,
      call: Call,
  ) {
    delegate.connectionAcquired(connection, call)
  }

  override fun connectionReleased(
      connection: Connection,
      call: Call,
  ) {
    delegate.connectionReleased(connection, call)
  }

  override fun noNewExchanges(connection: Connection) {
    delegate.noNewExchanges(connection)
  }

  companion object {
    val Route.defaultTracingLabel: String
      get() = this.address.url.host
  }
}
