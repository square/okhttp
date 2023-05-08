package okhttp3.internal.http2.flowcontrol

import java.util.concurrent.atomic.AtomicLong

class WindowCounter(
  val streamId: Int
) {
  /** The total number of bytes consumed. */
  private val _total: AtomicLong = AtomicLong(0)

  /** The total number of bytes acknowledged by outgoing `WINDOW_UPDATE` frames. */
  private val _acknowledged: AtomicLong = AtomicLong(0)

  val total: Long
    get() = _total.get()

  val acknowledged: Long
    get() = _acknowledged.get()

  val unacknowledged: Long
    @Synchronized get() = _total.get() - _acknowledged.get()

  @Synchronized
  fun increase(total: Long = 0, acknowledged: Long = 0) {
    check(total >= 0)
    check(acknowledged >= 0)

    this._total.addAndGet(total)
    this._acknowledged.addAndGet(acknowledged)

    check(this._acknowledged.get() <= this._total.get())
  }

  override fun toString(): String {
    return "WindowCounter(streamId=$streamId, total=$total, acknowledged=$acknowledged, unacknowledged=$unacknowledged)"
  }
}
