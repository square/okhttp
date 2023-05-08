package okhttp3.internal.http2.flowcontrol

import okhttp3.internal.http2.Http2Connection.Companion.OKHTTP_CLIENT_WINDOW_SIZE
import okhttp3.internal.http2.Settings
import okhttp3.internal.http2.flowcontrol.Http2FlowControlStrategy.ConnectionEvent
import okhttp3.internal.http2.flowcontrol.Http2FlowControlStrategy.ConnectionEvent.*

/**
 * Updated OkHttp 5 flow control strategy, with the following properties.
 *
 * - Tracks stream data as received.
 * - Applies the default of 16MiB for both stream and connection windows.
 * - Waits until 50% of stream or connection window is reached, before sending a window update.
 * - Primarily releases connection bytes as soon as the data is received and written to stream buffers, meaning
 *   memory use is higher, as total bytes in buffers may reach [clientWindowSize] * number of streams.
 */
class SoonerHttp2FlowControlStrategy(
  val clientWindowSize: Int = OKHTTP_CLIENT_WINDOW_SIZE
) : Http2FlowControlStrategy {
  override val trackOnReceive: Boolean = true

  override val initialSettings: Settings
    get() {
      return Settings().apply {
        // Flow control was designed more for servers, or proxies than edge clients. If we are a client,
        // set the flow control window to 16MiB.  This avoids thrashing window updates every 64KiB, yet
        // small enough to avoid blowing up the heap.
        set(Settings.INITIAL_WINDOW_SIZE, clientWindowSize)
      }
    }

  override fun initialConnectionWindowUpdate(): Long {
    return (clientWindowSize - Settings.DEFAULT_INITIAL_WINDOW_SIZE).toLong()
  }

  override fun connectionBytes(windowCounter: WindowCounter, event: ConnectionEvent): Long? =
    when (event) {
      Consumed, DiscardedUnconsumed -> halfUnacknowledged(windowCounter)
      RstStream, DiscardedUnexpected, Received -> null
    }

  override fun streamBytesOnConsumed(windowCounter: WindowCounter): Long? = halfUnacknowledged(windowCounter)

  private fun halfUnacknowledged(windowCounter: WindowCounter): Long? {
    val unacknowledged = windowCounter.unacknowledged
    return if (unacknowledged >= clientWindowSize / 2)
      unacknowledged
    else
      null
  }
}
