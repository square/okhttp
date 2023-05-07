package okhttp3.internal.http2.flowcontrol

import okhttp3.internal.http2.Http2Connection.Companion.OKHTTP_CLIENT_WINDOW_SIZE
import okhttp3.internal.http2.Settings

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

  override fun connectionBytesOnRstStream(windowCounter: WindowCounter): Long? = halfUnacknowledged(windowCounter)

  override fun connectionBytesOnReceived(windowCounter: WindowCounter): Long? = halfUnacknowledged(windowCounter)

  override fun streamBytesOnConsumed(windowCounter: WindowCounter): Long? = halfUnacknowledged(windowCounter)

  private fun halfUnacknowledged(windowCounter: WindowCounter): Long? {
    val unacknowledged = windowCounter.unacknowledged
    return if (unacknowledged >= clientWindowSize / 2)
      unacknowledged
    else
      null
  }
}
