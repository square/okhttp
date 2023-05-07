package okhttp3.internal.http2

import okhttp3.internal.http2.flowcontrol.Http2FlowControlStrategy
import okhttp3.internal.http2.flowcontrol.SoonerHttp2FlowControlStrategy
import org.assertj.core.api.AbstractLongAssert
import org.junit.jupiter.api.Test

class SoonerHttp2ConnectionTest: Http2ConnectionTest() {
  override fun flowControlStrategy(windowSize: Int): Http2FlowControlStrategy {
    return SoonerHttp2FlowControlStrategy(windowSize)
  }

  @Test
  fun testX() {
    readSendsWindowUpdate()
  }

  @Test
  fun testX2() {
    readSendsWindowUpdateHttp2()
  }

  override fun <T : AbstractLongAssert<T>?> AbstractLongAssert<T>.isInAcceptableRange(expected: Long, max: Long): T =
    this.isBetween(expected, max)
}
