/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.ws

import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RealCall
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.Task
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.Exchange
import okhttp3.internal.ws.WebSocketProtocol.CLOSE_CLIENT_GOING_AWAY
import okhttp3.internal.ws.WebSocketProtocol.CLOSE_MESSAGE_MAX
import okhttp3.internal.ws.WebSocketProtocol.OPCODE_BINARY
import okhttp3.internal.ws.WebSocketProtocol.OPCODE_TEXT
import okhttp3.internal.ws.WebSocketProtocol.validateCloseCode
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.buffer
import java.io.Closeable
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS

class RealWebSocket(
  taskRunner: TaskRunner,
  /** The application's original request unadulterated by web socket headers. */
  private val originalRequest: Request,
  internal val listener: WebSocketListener,
  private val random: Random,
  private val pingIntervalMillis: Long
) : WebSocket, WebSocketReader.FrameCallback {
  private val key: String

  /** Non-null for client web sockets. These can be canceled. */
  private var call: Call? = null

  /** This task processes the outgoing queues. Call [runWriter] to after enqueueing. */
  private var writerTask: Task? = null

  /** Null until this web socket is connected. Only accessed by the reader thread. */
  private var reader: WebSocketReader? = null

  // All mutable web socket state is guarded by this.

  /** Null until this web socket is connected. Note that messages may be enqueued before that. */
  private var writer: WebSocketWriter? = null

  /** Used for writes, pings, and close timeouts. */
  private var taskQueue = taskRunner.newQueue()

  /** Names this web socket for observability and debugging. */
  private var name: String? = null

  /**
   * The streams held by this web socket. This is non-null until all incoming messages have been
   * read and all outgoing messages have been written. It is closed when both reader and writer are
   * exhausted, or if there is any failure.
   */
  private var streams: Streams? = null

  /** Outgoing pongs in the order they should be written. */
  private val pongQueue = ArrayDeque<ByteString>()

  /** Outgoing messages and close frames in the order they should be written. */
  private val messageAndCloseQueue = ArrayDeque<Any>()

  /** The total size in bytes of enqueued but not yet transmitted messages. */
  private var queueSize = 0L

  /** True if we've enqueued a close frame. No further message frames will be enqueued. */
  private var enqueuedClose = false

  /** The close code from the peer, or -1 if this web socket has not yet read a close frame. */
  private var receivedCloseCode = -1

  /** The close reason from the peer, or null if this web socket has not yet read a close frame. */
  private var receivedCloseReason: String? = null

  /** True if this web socket failed and the listener has been notified. */
  private var failed = false

  /** Total number of pings sent by this web socket. */
  private var sentPingCount = 0

  /** Total number of pings received by this web socket. */
  private var receivedPingCount = 0

  /** Total number of pongs received by this web socket. */
  private var receivedPongCount = 0

  /** True if we have sent a ping that is still awaiting a reply. */
  private var awaitingPong = false

  init {
    require("GET" == originalRequest.method) {
      "Request must be GET: ${originalRequest.method}"
    }

    this.key = ByteArray(16).apply { random.nextBytes(this) }.toByteString().base64()
  }

  override fun request(): Request = originalRequest

  @Synchronized override fun queueSize(): Long = queueSize

  override fun cancel() {
    call!!.cancel()
  }

  fun connect(client: OkHttpClient) {
    val webSocketClient = client.newBuilder()
        .eventListener(EventListener.NONE)
        .protocols(ONLY_HTTP1)
        .build()
    val request = originalRequest.newBuilder()
        .header("Upgrade", "websocket")
        .header("Connection", "Upgrade")
        .header("Sec-WebSocket-Key", key)
        .header("Sec-WebSocket-Version", "13")
        .build()
    call = RealCall.newRealCall(webSocketClient, request, forWebSocket = true)
    call!!.enqueue(object : Callback {
      override fun onResponse(call: Call, response: Response) {
        val exchange = response.exchange
        val streams: Streams
        try {
          checkUpgradeSuccess(response, exchange)
          streams = exchange!!.newWebSocketStreams()
        } catch (e: IOException) {
          exchange?.webSocketUpgradeFailed()
          failWebSocket(e, response)
          response.closeQuietly()
          return
        }

        // Process all web socket messages.
        try {
          val name = "OkHttp WebSocket ${request.url.redact()}"
          initReaderAndWriter(name, streams)
          listener.onOpen(this@RealWebSocket, response)
          loopReader()
        } catch (e: Exception) {
          failWebSocket(e, null)
        }
      }

      override fun onFailure(call: Call, e: IOException) {
        failWebSocket(e, null)
      }
    })
  }

  @Throws(IOException::class)
  internal fun checkUpgradeSuccess(response: Response, exchange: Exchange?) {
    if (response.code != 101) {
      throw ProtocolException(
          "Expected HTTP 101 response but was '${response.code} ${response.message}'")
    }

    val headerConnection = response.header("Connection")
    if (!"Upgrade".equals(headerConnection, ignoreCase = true)) {
      throw ProtocolException(
          "Expected 'Connection' header value 'Upgrade' but was '$headerConnection'")
    }

    val headerUpgrade = response.header("Upgrade")
    if (!"websocket".equals(headerUpgrade, ignoreCase = true)) {
      throw ProtocolException(
          "Expected 'Upgrade' header value 'websocket' but was '$headerUpgrade'")
    }

    val headerAccept = response.header("Sec-WebSocket-Accept")
    val acceptExpected = (key + WebSocketProtocol.ACCEPT_MAGIC).encodeUtf8().sha1().base64()
    if (acceptExpected != headerAccept) {
      throw ProtocolException(
          "Expected 'Sec-WebSocket-Accept' header value '$acceptExpected' but was '$headerAccept'")
    }

    if (exchange == null) {
      throw ProtocolException("Web Socket exchange missing: bad interceptor?")
    }
  }

  @Throws(IOException::class)
  fun initReaderAndWriter(name: String, streams: Streams) {
    synchronized(this) {
      this.name = name
      this.streams = streams
      this.writer = WebSocketWriter(streams.client, streams.sink, random)
      this.writerTask = WriterTask()
      if (pingIntervalMillis != 0L) {
        val pingIntervalNanos = MILLISECONDS.toNanos(pingIntervalMillis)
        taskQueue.schedule("$name ping", pingIntervalNanos) {
          writePingFrame()
          return@schedule pingIntervalNanos
        }
      }
      if (messageAndCloseQueue.isNotEmpty()) {
        runWriter() // Send messages that were enqueued before we were connected.
      }
    }

    reader = WebSocketReader(streams.client, streams.source, this)
  }

  /** Receive frames until there are no more. Invoked only by the reader thread. */
  @Throws(IOException::class)
  fun loopReader() {
    while (receivedCloseCode == -1) {
      // This method call results in one or more onRead* methods being called on this thread.
      reader!!.processNextFrame()
    }
  }

  /**
   * For testing: receive a single frame and return true if there are more frames to read. Invoked
   * only by the reader thread.
   */
  @Throws(IOException::class)
  fun processNextFrame(): Boolean {
    return try {
      reader!!.processNextFrame()
      receivedCloseCode == -1
    } catch (e: Exception) {
      failWebSocket(e, null)
      false
    }
  }

  /** For testing: wait until the web socket's executor has terminated. */
  @Throws(InterruptedException::class)
  fun awaitTermination(timeout: Long, timeUnit: TimeUnit) {
    taskQueue.awaitIdle(timeUnit.toNanos(timeout))
  }

  /** For testing: force this web socket to release its threads. */
  @Throws(InterruptedException::class)
  fun tearDown() {
    taskQueue.shutdown()
    taskQueue.awaitIdle(TimeUnit.SECONDS.toNanos(10L))
  }

  @Synchronized fun sentPingCount(): Int = sentPingCount

  @Synchronized fun receivedPingCount(): Int = receivedPingCount

  @Synchronized fun receivedPongCount(): Int = receivedPongCount

  @Throws(IOException::class)
  override fun onReadMessage(text: String) {
    listener.onMessage(this, text)
  }

  @Throws(IOException::class)
  override fun onReadMessage(bytes: ByteString) {
    listener.onMessage(this, bytes)
  }

  @Synchronized override fun onReadPing(payload: ByteString) {
    // Don't respond to pings after we've failed or sent the close frame.
    if (failed || enqueuedClose && messageAndCloseQueue.isEmpty()) return

    pongQueue.add(payload)
    runWriter()
    receivedPingCount++
  }

  @Synchronized override fun onReadPong(payload: ByteString) {
    // This API doesn't expose pings.
    receivedPongCount++
    awaitingPong = false
  }

  override fun onReadClose(code: Int, reason: String) {
    require(code != -1)

    var toClose: Streams? = null
    synchronized(this) {
      check(receivedCloseCode == -1) { "already closed" }
      receivedCloseCode = code
      receivedCloseReason = reason
      if (enqueuedClose && messageAndCloseQueue.isEmpty()) {
        toClose = this.streams
        this.streams = null
        this.taskQueue.shutdown()
      }
    }

    try {
      listener.onClosing(this, code, reason)

      if (toClose != null) {
        listener.onClosed(this, code, reason)
      }
    } finally {
      toClose?.closeQuietly()
    }
  }

  // Writer methods to enqueue frames. They'll be sent asynchronously by the writer thread.

  override fun send(text: String): Boolean {
    return send(text.encodeUtf8(), OPCODE_TEXT)
  }

  override fun send(bytes: ByteString): Boolean {
    return send(bytes, OPCODE_BINARY)
  }

  @Synchronized private fun send(data: ByteString, formatOpcode: Int): Boolean {
    // Don't send new frames after we've failed or enqueued a close frame.
    if (failed || enqueuedClose) return false

    // If this frame overflows the buffer, reject it and close the web socket.
    if (queueSize + data.size > MAX_QUEUE_SIZE) {
      close(CLOSE_CLIENT_GOING_AWAY, null)
      return false
    }

    // Enqueue the message frame.
    queueSize += data.size.toLong()
    messageAndCloseQueue.add(Message(formatOpcode, data))
    runWriter()
    return true
  }

  @Synchronized fun pong(payload: ByteString): Boolean {
    // Don't send pongs after we've failed or sent the close frame.
    if (failed || enqueuedClose && messageAndCloseQueue.isEmpty()) return false

    pongQueue.add(payload)
    runWriter()
    return true
  }

  override fun close(code: Int, reason: String?): Boolean {
    return close(code, reason, CANCEL_AFTER_CLOSE_MILLIS)
  }

  @Synchronized fun close(
    code: Int,
    reason: String?,
    cancelAfterCloseMillis: Long
  ): Boolean {
    validateCloseCode(code)

    var reasonBytes: ByteString? = null
    if (reason != null) {
      reasonBytes = reason.encodeUtf8()
      require(reasonBytes.size <= CLOSE_MESSAGE_MAX) {
        "reason.size() > $CLOSE_MESSAGE_MAX: $reason"
      }
    }

    if (failed || enqueuedClose) return false

    // Immediately prevent further frames from being enqueued.
    enqueuedClose = true

    // Enqueue the close frame.
    messageAndCloseQueue.add(Close(code, reasonBytes, cancelAfterCloseMillis))
    runWriter()
    return true
  }

  private fun runWriter() {
    assert(Thread.holdsLock(this))
    taskQueue.schedule(writerTask!!)
  }

  /**
   * Attempts to remove a single frame from a queue and send it. This prefers to write urgent pongs
   * before less urgent messages and close frames. For example it's possible that a caller will
   * enqueue messages followed by pongs, but this sends pongs followed by messages. Pongs are always
   * written in the order they were enqueued.
   *
   * If a frame cannot be sent - because there are none enqueued or because the web socket is not
   * connected - this does nothing and returns false. Otherwise this returns true and the caller
   * should immediately invoke this method again until it returns false.
   *
   * This method may only be invoked by the writer thread. There may be only thread invoking this
   * method at a time.
   */
  @Throws(IOException::class)
  internal fun writeOneFrame(): Boolean {
    val writer: WebSocketWriter?
    val pong: ByteString?
    var messageOrClose: Any? = null
    var receivedCloseCode = -1
    var receivedCloseReason: String? = null
    var streamsToClose: Streams? = null

    synchronized(this@RealWebSocket) {
      if (failed) {
        return false // Failed web socket.
      }

      writer = this.writer
      pong = pongQueue.poll()
      if (pong == null) {
        messageOrClose = messageAndCloseQueue.poll()
        if (messageOrClose is Close) {
          receivedCloseCode = this.receivedCloseCode
          receivedCloseReason = this.receivedCloseReason
          if (receivedCloseCode != -1) {
            streamsToClose = this.streams
            this.streams = null
            this.taskQueue.shutdown()
          } else {
            // When we request a graceful close also schedule a cancel of the web socket.
            val cancelAfterCloseMillis = (messageOrClose as Close).cancelAfterCloseMillis
            taskQueue.execute("$name cancel", MILLISECONDS.toNanos(cancelAfterCloseMillis)) {
              cancel()
            }
          }
        } else if (messageOrClose == null) {
          return false // The queue is exhausted.
        }
      }
    }

    try {
      if (pong != null) {
        writer!!.writePong(pong)
      } else if (messageOrClose is Message) {
        val data = (messageOrClose as Message).data
        val sink = writer!!.newMessageSink(
            (messageOrClose as Message).formatOpcode, data.size.toLong()).buffer()
        sink.write(data)
        sink.close()
        synchronized(this) {
          queueSize -= data.size.toLong()
        }
      } else if (messageOrClose is Close) {
        val close = messageOrClose as Close
        writer!!.writeClose(close.code, close.reason)

        // We closed the writer: now both reader and writer are closed.
        if (streamsToClose != null) {
          listener.onClosed(this, receivedCloseCode, receivedCloseReason!!)
        }
      } else {
        throw AssertionError()
      }

      return true
    } finally {
      streamsToClose?.closeQuietly()
    }
  }

  internal fun writePingFrame() {
    val writer: WebSocketWriter?
    val failedPing: Int
    synchronized(this) {
      if (failed) return
      writer = this.writer
      failedPing = if (awaitingPong) sentPingCount else -1
      sentPingCount++
      awaitingPong = true
    }

    if (failedPing != -1) {
      failWebSocket(SocketTimeoutException("sent ping but didn't receive pong within " +
          "${pingIntervalMillis}ms (after ${failedPing - 1} successful ping/pongs)"), null)
      return
    }

    try {
      writer!!.writePing(ByteString.EMPTY)
    } catch (e: IOException) {
      failWebSocket(e, null)
    }
  }

  fun failWebSocket(e: Exception, response: Response?) {
    val streamsToClose: Streams?
    synchronized(this) {
      if (failed) return // Already failed.
      failed = true
      streamsToClose = this.streams
      this.streams = null
      taskQueue.shutdown()
    }

    try {
      listener.onFailure(this, e, response)
    } finally {
      streamsToClose?.closeQuietly()
    }
  }

  internal class Message(
    val formatOpcode: Int,
    val data: ByteString
  )

  internal class Close(
    val code: Int,
    val reason: ByteString?,
    val cancelAfterCloseMillis: Long
  )

  abstract class Streams(
    val client: Boolean,
    val source: BufferedSource,
    val sink: BufferedSink
  ) : Closeable

  private inner class WriterTask : Task("$name writer") {
    override fun runOnce(): Long {
      try {
        if (writeOneFrame()) return 0L
      } catch (e: IOException) {
        failWebSocket(e, null)
      }
      return -1L
    }
  }

  companion object {
    private val ONLY_HTTP1 = listOf(Protocol.HTTP_1_1)

    /**
     * The maximum number of bytes to enqueue. Rather than enqueueing beyond this limit we tear down
     * the web socket! It's possible that we're writing faster than the peer can read.
     */
    private const val MAX_QUEUE_SIZE = 16L * 1024 * 1024 // 16 MiB.

    /**
     * The maximum amount of time after the client calls [close] to wait for a graceful shutdown. If
     * the server doesn't respond the web socket will be canceled.
     */
    private const val CANCEL_AFTER_CLOSE_MILLIS = 60L * 1000
  }
}
