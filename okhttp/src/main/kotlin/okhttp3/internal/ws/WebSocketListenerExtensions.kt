package okhttp3.internal.ws

import okhttp3.*
import okio.ByteString


fun OkHttpClient.newWebSocket(request: Request, block: WebSocketListenerExt.() -> Unit): WebSocket {
  val listener = WebSocketListenerImpl().apply(block)
  return this.newWebSocket(request, listener)
}

interface WebSocketListenerExt {
  fun onOpen(block: (webSocket: WebSocket, response: Response) -> Unit)
  fun onFailure(block: (webSocket: WebSocket, t: Throwable, response: Response?) -> Unit)
  fun onClosing(block: (webSocket: WebSocket, code: Int, reason: String) -> Unit)
  fun onMessage(block: (webSocket: WebSocket, text: String) -> Unit)
  fun onMessageByte(block: (webSocket: WebSocket, bytes: ByteString) -> Unit)
  fun onClosed(block: (webSocket: WebSocket, code: Int, reason: String) -> Unit)
}

private class WebSocketListenerImpl : WebSocketListener(), WebSocketListenerExt {

  private var _onOpen: ((webSocket: WebSocket, response: Response) -> Unit)? = null
  private var _onFailure: ((webSocket: WebSocket, t: Throwable, response: Response?) -> Unit)? =
      null
  private var _onClosing: ((webSocket: WebSocket, code: Int, reason: String) -> Unit)? = null
  private var _onMessage: ((webSocket: WebSocket, text: String) -> Unit)? = null
  private var _onMessageByte: ((webSocket: WebSocket, bytes: ByteString) -> Unit)? = null
  private var _onClosed: ((webSocket: WebSocket, code: Int, reason: String) -> Unit)? = null

  override fun onOpen(webSocket: WebSocket, response: Response) {
    _onOpen?.invoke(webSocket, response)
  }

  override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
    _onFailure?.invoke(webSocket, t, response)
  }

  override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
    _onClosing?.invoke(webSocket, code, reason)
  }

  override fun onMessage(webSocket: WebSocket, text: String) {
    _onMessage?.invoke(webSocket, text)
  }

  override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
    _onMessageByte?.invoke(webSocket, bytes)
  }

  override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
    _onClosed?.invoke(webSocket, code, reason)
  }

  override fun onOpen(block: (webSocket: WebSocket, response: Response) -> Unit) {
    _onOpen = block
  }

  override fun onFailure(block: (webSocket: WebSocket, t: Throwable, response: Response?) -> Unit) {
    _onFailure = block
  }

  override fun onClosing(block: (webSocket: WebSocket, code: Int, reason: String) -> Unit) {
    _onClosing = block
  }

  override fun onMessage(block: (webSocket: WebSocket, text: String) -> Unit) {
    _onMessage = block
  }

  override fun onMessageByte(block: (webSocket: WebSocket, bytes: ByteString) -> Unit) {
    _onMessageByte = block
  }

  override fun onClosed(block: (webSocket: WebSocket, code: Int, reason: String) -> Unit) {
    _onClosed = block
  }
}
