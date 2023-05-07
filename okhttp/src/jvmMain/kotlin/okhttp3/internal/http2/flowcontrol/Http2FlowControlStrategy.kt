package okhttp3.internal.http2.flowcontrol

import okhttp3.internal.http2.Header
import okhttp3.internal.http2.Settings

interface Http2FlowControlStrategy {

  val initialSettings: Settings

  fun initialStreamWindowUpdate(streamId: Int, requestHeaders: List<Header>): Long? = null

  fun initialConnectionWindowUpdate(): Long? = null

  fun connectionBytesOnRstStream(windowCounter: WindowCounter): Long? = null

  fun connectionBytesOnConsumed(windowCounter: WindowCounter): Long? = null

  fun connectionBytesOnDiscarded(windowCounter: WindowCounter): Long? = null
}
