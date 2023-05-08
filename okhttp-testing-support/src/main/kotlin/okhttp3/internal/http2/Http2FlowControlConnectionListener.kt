package okhttp3.internal.http2

import okhttp3.ConnectionListener
import okhttp3.internal.http2.flowcontrol.WindowCounter

class Http2FlowControlConnectionListener: ConnectionListener(), FlowControlListener {
  val start = System.currentTimeMillis()
  override fun flowControlWindowChanged(streamId: Int, windowCounter: WindowCounter) {
    println("${System.currentTimeMillis() - start},$streamId,${windowCounter.unacknowledged}")
  }
}
