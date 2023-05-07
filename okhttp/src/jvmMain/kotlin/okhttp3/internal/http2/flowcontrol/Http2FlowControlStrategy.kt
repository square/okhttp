package okhttp3.internal.http2.flowcontrol

import okhttp3.internal.http2.Header
import okhttp3.internal.http2.Settings

/**
 * An implementation of Flow Control for Http/2 Connection and Streams.
 *
 * Double counting of bytes should not be possible because of external
 * constraints, also enforced in WindowCounter.
 */
interface Http2FlowControlStrategy {

  /**
   * Whether to count stream bytes as unackknowledged immediately as received as frames,
   * or only when the response body is consumed.  Should be mostly a theoretical difference
   * but included to replicated OkHttp 4 behaviour.
   */
  val trackOnReceive: Boolean

  /**
   * The initial connection settings to send immediately on establishing a connection.
   */
  val initialSettings: Settings

  /**
   * How many additional window update bytes to send for this specific stream over
   * [Settings.initialWindowSize].
   */
  fun initialStreamWindowUpdate(streamId: Int, requestHeaders: List<Header>): Long? = null

  /**
   * How many additional window update bytes to send for the connection over the [Settings.DEFAULT_INITIAL_WINDOW_SIZE].
   */
  fun initialConnectionWindowUpdate(): Long? = null

  /**
   * How many window update bytes to release on the connection for data on an unknown or closed stream,
   * resulting in RST_STREAM.
   */
  fun connectionBytesOnRstStream(windowCounter: WindowCounter): Long? = null

  /**
   * How many window update bytes to release on the connection when data is consumed by the application, removing
   * it from stream buffers.
   */
  fun connectionBytesOnConsumed(windowCounter: WindowCounter): Long? = null

  /**
   * How many window update bytes to release on the connection when data is discarded on a failed stream.
   */
  fun connectionBytesOnDiscarded(windowCounter: WindowCounter): Long? = null

  /**
   * How many window update bytes to release on the connection when data is received into the stream buffers.
   */
  fun connectionBytesOnReceived(windowCounter: WindowCounter): Long? = null

  /**
   * How many window update bytes to release on the stream when data is consumed by the application.
   */
  fun streamBytesOnConsumed(windowCounter: WindowCounter): Long? = null
}
