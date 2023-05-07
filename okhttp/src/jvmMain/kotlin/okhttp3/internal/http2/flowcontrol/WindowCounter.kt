package okhttp3.internal.http2.flowcontrol

data class WindowCounter(
  /** The total number of bytes consumed. */
  val total: Long = 0,
  /** The total number of bytes acknowledged by outgoing `WINDOW_UPDATE` frames. */
  val acknowledged: Long = 0
) {
  init {
    check(acknowledged >= 0)
    check(total >= acknowledged)
  }

  val unacknowledged: Long
    get() = total - acknowledged

  fun increase(total: Long = 0, acknowledged: Long = 0): WindowCounter {
    check(total >= 0)
    check(acknowledged >= 0)

    return copy(total = this.total + total, acknowledged = this.acknowledged + acknowledged)
  }
}
