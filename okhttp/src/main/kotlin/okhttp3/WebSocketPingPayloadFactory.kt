package okhttp3

import okio.ByteString

interface WebSocketPingPayloadFactory {
  /**
   * Enable customization of ping payload by overriding
   */
  fun generatePayload(): ByteString {
    return ByteString.EMPTY
  }
}