package okhttp3.internal.http2.flowcontrol

data class WindowCounter(
  /** The total number of bytes consumed. */
  val total: Long = 0,
  /** The total number of bytes acknowledged by outgoing `WINDOW_UPDATE` frames. */
  val acknowledged: Long = 0
) {
  val unacknowledged: Long
    get() = total - acknowledged
}
