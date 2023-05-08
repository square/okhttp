package okhttp3.internal.http2

import okhttp3.internal.http2.flowcontrol.WindowCounter

interface FlowControlListener {
  fun flowControlWindowChanged(streamId: Int, windowCounter: WindowCounter)

  object None: FlowControlListener {
    override fun flowControlWindowChanged(streamId: Int, windowCounter: WindowCounter) {
    }
  }
}
