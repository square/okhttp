package okhttp3.internal.http2

import okhttp3.internal.http2.flowcontrol.Http2FlowControlStrategy
import okhttp3.internal.http2.flowcontrol.SoonerHttp2FlowControlStrategy

class SoonerHttp2ConnectionTest: Http2ConnectionTest() {
  override fun flowControlStrategy(windowSize: Int): Http2FlowControlStrategy {
    return SoonerHttp2FlowControlStrategy(windowSize)
  }
}
