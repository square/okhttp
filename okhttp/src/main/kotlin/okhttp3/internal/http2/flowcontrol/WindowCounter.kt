package okhttp3.internal.http2.flowcontrol

class WindowCounter(
  val streamId: Int,
) {
  /** The total number of bytes consumed. */
  var total: Long = 0L
    private set

  /** The total number of bytes acknowledged by outgoing `WINDOW_UPDATE` frames. */
  var acknowledged: Long = 0L
    private set

  val unacknowledged: Long
    @Synchronized get() = total - acknowledged

  @Synchronized
  fun update(
    total: Long = 0,
    acknowledged: Long = 0,
  ) {
    check(total >= 0)
    check(acknowledged >= 0)

    this.total += total
    this.acknowledged += acknowledged

    check(this.acknowledged <= this.total)
  }

  override fun toString(): String {
    return "WindowCounter(streamId=$streamId, total=$total, acknowledged=$acknowledged, unacknowledged=$unacknowledged)"
  }
}
