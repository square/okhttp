/*
 * Copyright (C) 2011 The Android Open Source Project
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
package okhttp3.internal.http2

import okhttp3.internal.Util
import okhttp3.internal.connectionName
import okhttp3.internal.execute
import okhttp3.internal.http2.ErrorCode.REFUSED_STREAM
import okhttp3.internal.http2.Settings.Companion.DEFAULT_INITIAL_WINDOW_SIZE
import okhttp3.internal.ignoreIoExceptions
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.Platform.Companion.INFO
import okhttp3.internal.threadName
import okhttp3.internal.tryExecute
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.buffer
import okio.sink
import okio.source
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * A socket connection to a remote peer. A connection hosts streams which can send and receive
 * data.
 *
 * Many methods in this API are **synchronous:** the call is completed before the method returns.
 * This is typical for Java but atypical for HTTP/2. This is motivated by exception transparency:
 * an [IOException] that was triggered by a certain caller can be caught and handled by that caller.
 */
class Http2Connection internal constructor(builder: Builder) : Closeable {

  // Internal state of this connection is guarded by 'this'. No blocking operations may be
  // performed while holding this lock!
  //
  // Socket writes are guarded by frameWriter.
  //
  // Socket reads are unguarded but are only made by the reader thread.
  //
  // Certain operations (like SYN_STREAM) need to synchronize on both the frameWriter (to do
  // blocking I/O) and this (to create streams). Such operations must synchronize on 'this' last.
  // This ensures that we never wait for a blocking operation while holding 'this'.

  /** True if this peer initiated the connection.  */
  internal val client: Boolean = builder.client

  /**
   * User code to run in response to incoming streams or settings. Calls to this are always invoked
   * on [listenerExecutor].
   */
  internal val listener: Listener = builder.listener
  internal val streams = mutableMapOf<Int, Http2Stream>()
  internal val connectionName: String = builder.connectionName
  internal var lastGoodStreamId = 0

  /** http://tools.ietf.org/html/draft-ietf-httpbis-http2-17#section-5.1.1 */
  internal var nextStreamId = if (builder.client) 3 else 2

  @get:Synchronized var isShutdown = false
    internal set

  /** Asynchronously writes frames to the outgoing socket.  */
  private val writerExecutor = ScheduledThreadPoolExecutor(1,
      Util.threadFactory(Util.format("OkHttp %s Writer", connectionName), false))

  /** Ensures push promise callbacks events are sent in order per stream.  */
  // Like newSingleThreadExecutor, except lazy creates the thread.
  private val pushExecutor = ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(),
      Util.threadFactory(Util.format("OkHttp %s Push Observer", connectionName), true))

  /** User code to run in response to push promise events.  */
  internal val pushObserver: PushObserver = builder.pushObserver

  /** True if we have sent a ping that is still awaiting a reply.  */
  private var awaitingPong = false

  /** Settings we communicate to the peer.  */
  val okHttpSettings = Settings().apply {
    // Flow control was designed more for servers, or proxies than edge clients. If we are a client,
    // set the flow control window to 16MiB.  This avoids thrashing window updates every 64KiB, yet
    // small enough to avoid blowing up the heap.
    if (builder.client) {
      set(Settings.INITIAL_WINDOW_SIZE, OKHTTP_CLIENT_WINDOW_SIZE)
    }
  }

  /** Settings we receive from the peer.  */
  // TODO: MWS will need to guard on this setting before attempting to push.
  val peerSettings = Settings().apply {
    set(Settings.INITIAL_WINDOW_SIZE, DEFAULT_INITIAL_WINDOW_SIZE)
    set(Settings.MAX_FRAME_SIZE, Http2.INITIAL_MAX_FRAME_SIZE)
  }

  /**
   * The total number of bytes consumed by the application, but not yet acknowledged by sending a
   * `WINDOW_UPDATE` frame on this connection.
   */
  // Visible for testing
  var unacknowledgedBytesRead = 0L
    private set

  /**
   * Count of bytes that can be written on the connection before receiving a window update.
   */
  // Visible for testing
  var bytesLeftInWriteWindow: Long = peerSettings.initialWindowSize.toLong()
    internal set

  internal var receivedInitialPeerSettings = false
  internal val socket: Socket = builder.socket
  val writer = Http2Writer(builder.sink, client)

  // Visible for testing
  val readerRunnable = ReaderRunnable(Http2Reader(builder.source, client))

  // Guarded by this.
  internal val currentPushRequests = mutableSetOf<Int>()

  init {
    if (builder.pingIntervalMillis != 0) {
      writerExecutor.scheduleAtFixedRate({
        threadName("OkHttp $connectionName ping") {
          writePing(false, 0, 0)
        }
      }, builder.pingIntervalMillis.toLong(), builder.pingIntervalMillis.toLong(), MILLISECONDS)
    }
  }

  /**
   * Returns the number of [open streams][Http2Stream.isOpen] on this connection.
   */
  @Synchronized fun openStreamCount(): Int = streams.size

  @Synchronized fun getStream(id: Int): Http2Stream? = streams[id]

  @Synchronized internal fun removeStream(streamId: Int): Http2Stream? {
    val stream = streams.remove(streamId)

    // The removed stream may be blocked on a connection-wide window update.
    (this as Object).notifyAll()

    return stream
  }

  @Synchronized fun maxConcurrentStreams(): Int =
      peerSettings.getMaxConcurrentStreams(Integer.MAX_VALUE)

  @Synchronized internal fun updateConnectionFlowControl(read: Long) {
    unacknowledgedBytesRead += read
    if (unacknowledgedBytesRead >= okHttpSettings.initialWindowSize / 2) {
      writeWindowUpdateLater(0, unacknowledgedBytesRead)
      unacknowledgedBytesRead = 0
    }
  }

  /**
   * Returns a new server-initiated stream.
   *
   * @param associatedStreamId the stream that triggered the sender to create this stream.
   * @param out true to create an output stream that we can use to send data to the remote peer.
   *     Corresponds to `FLAG_FIN`.
   */
  @Throws(IOException::class)
  fun pushStream(
    associatedStreamId: Int,
    requestHeaders: List<Header>,
    out: Boolean
  ): Http2Stream {
    check(!client) { "Client cannot push requests." }
    return newStream(associatedStreamId, requestHeaders, out)
  }

  /**
   * Returns a new locally-initiated stream.
   *
   * @param out true to create an output stream that we can use to send data to the remote peer.
   *     Corresponds to `FLAG_FIN`.
   */
  @Throws(IOException::class)
  fun newStream(
    requestHeaders: List<Header>,
    out: Boolean
  ): Http2Stream {
    return newStream(0, requestHeaders, out)
  }

  @Throws(IOException::class)
  private fun newStream(
    associatedStreamId: Int,
    requestHeaders: List<Header>,
    out: Boolean
  ): Http2Stream {
    val outFinished = !out
    val inFinished = false
    val flushHeaders: Boolean
    val stream: Http2Stream
    val streamId: Int

    synchronized(writer) {
      synchronized(this) {
        if (nextStreamId > Integer.MAX_VALUE / 2) {
          shutdown(REFUSED_STREAM)
        }
        if (isShutdown) {
          throw ConnectionShutdownException()
        }
        streamId = nextStreamId
        nextStreamId += 2
        stream = Http2Stream(streamId, this, outFinished, inFinished, null)
        flushHeaders = (!out || bytesLeftInWriteWindow == 0L || stream.bytesLeftInWriteWindow == 0L)
        if (stream.isOpen) {
          streams[streamId] = stream
        }
      }
      if (associatedStreamId == 0) {
        writer.headers(outFinished, streamId, requestHeaders)
      } else {
        require(!client) { "client streams shouldn't have associated stream IDs" }
        // HTTP/2 has a PUSH_PROMISE frame.
        writer.pushPromise(associatedStreamId, streamId, requestHeaders)
      }
    }

    if (flushHeaders) {
      writer.flush()
    }

    return stream
  }

  @Throws(IOException::class)
  internal fun writeHeaders(
    streamId: Int,
    outFinished: Boolean,
    alternating: List<Header>
  ) {
    writer.headers(outFinished, streamId, alternating)
  }

  /**
   * Callers of this method are not thread safe, and sometimes on application threads. Most often,
   * this method will be called to send a buffer worth of data to the peer.
   *
   * Writes are subject to the write window of the stream and the connection. Until there is a
   * window sufficient to send [byteCount], the caller will block. For example, a user of
   * `HttpURLConnection` who flushes more bytes to the output stream than the connection's write
   * window will block.
   *
   * Zero [byteCount] writes are not subject to flow control and will not block. The only use case
   * for zero [byteCount] is closing a flushed output stream.
   */
  @Throws(IOException::class)
  fun writeData(
    streamId: Int,
    outFinished: Boolean,
    buffer: Buffer?,
    byteCount: Long
  ) {
    // Empty data frames are not flow-controlled.
    if (byteCount == 0L) {
      writer.data(outFinished, streamId, buffer, 0)
      return
    }

    var byteCount = byteCount
    while (byteCount > 0L) {
      var toWrite: Int
      synchronized(this@Http2Connection) {
        try {
          while (bytesLeftInWriteWindow <= 0) {
            // Before blocking, confirm that the stream we're writing is still open. It's possible
            // that the stream has since been closed (such as if this write timed out.)
            if (!streams.containsKey(streamId)) {
              throw IOException("stream closed")
            }
            (this@Http2Connection as Object).wait() // Wait until we receive a WINDOW_UPDATE.
          }
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt() // Retain interrupted status.
          throw InterruptedIOException()
        }

        toWrite = minOf(byteCount, bytesLeftInWriteWindow).toInt()
        toWrite = minOf(toWrite, writer.maxDataLength())
        bytesLeftInWriteWindow -= toWrite.toLong()
      }

      byteCount -= toWrite.toLong()
      writer.data(outFinished && byteCount == 0L, streamId, buffer, toWrite)
    }
  }

  internal fun writeSynResetLater(
    streamId: Int,
    errorCode: ErrorCode
  ) {
    writerExecutor.tryExecute("OkHttp $connectionName stream $streamId") {
      try {
        writeSynReset(streamId, errorCode)
      } catch (e: IOException) {
        failConnection(e)
      }
    }
  }

  @Throws(IOException::class)
  internal fun writeSynReset(
    streamId: Int,
    statusCode: ErrorCode
  ) {
    writer.rstStream(streamId, statusCode)
  }

  internal fun writeWindowUpdateLater(
    streamId: Int,
    unacknowledgedBytesRead: Long
  ) {
    writerExecutor.tryExecute("OkHttp Window Update $connectionName stream $streamId") {
      try {
        writer.windowUpdate(streamId, unacknowledgedBytesRead)
      } catch (e: IOException) {
        failConnection(e)
      }
    }
  }

  fun writePing(
    reply: Boolean,
    payload1: Int,
    payload2: Int
  ) {
    if (!reply) {
      val failedDueToMissingPong: Boolean
      synchronized(this) {
        failedDueToMissingPong = awaitingPong
        awaitingPong = true
      }
      if (failedDueToMissingPong) {
        failConnection(null)
        return
      }
    }

    try {
      writer.ping(reply, payload1, payload2)
    } catch (e: IOException) {
      failConnection(e)
    }
  }

  /** For testing: sends a ping and waits for a pong.  */
  @Throws(InterruptedException::class)
  fun writePingAndAwaitPong() {
    writePing(false, 0x4f4b6f6b /* "OKok" */, -0xf607257 /* donut */)
    awaitPong()
  }

  /** For testing: waits until `requiredPongCount` pings have been received from the peer.  */
  @Synchronized @Throws(InterruptedException::class)
  fun awaitPong() {
    while (awaitingPong) {
      (this as Object).wait()
    }
  }

  @Throws(IOException::class)
  fun flush() {
    writer.flush()
  }

  /**
   * Degrades this connection such that new streams can neither be created locally, nor accepted
   * from the remote peer. Existing streams are not impacted. This is intended to permit an endpoint
   * to gracefully stop accepting new requests without harming previously established streams.
   */
  @Throws(IOException::class)
  fun shutdown(statusCode: ErrorCode) {
    synchronized(writer) {
      val lastGoodStreamId: Int
      synchronized(this) {
        if (isShutdown) {
          return
        }
        isShutdown = true
        lastGoodStreamId = this.lastGoodStreamId
      }
      // TODO: propagate exception message into debugData.
      // TODO: configure a timeout on the reader so that it doesn’t block forever.
      writer.goAway(lastGoodStreamId, statusCode, Util.EMPTY_BYTE_ARRAY)
    }
  }

  /**
   * Closes this connection. This cancels all open streams and unanswered pings. It closes the
   * underlying input and output streams and shuts down internal executor services.
   */
  override fun close() {
    close(ErrorCode.NO_ERROR, ErrorCode.CANCEL, null)
  }

  internal fun close(
    connectionCode: ErrorCode,
    streamCode: ErrorCode,
    cause: IOException?
  ) {
    assert(!Thread.holdsLock(this))
    ignoreIoExceptions {
      shutdown(connectionCode)
    }

    var streamsToClose: Array<Http2Stream>? = null
    synchronized(this) {
      if (streams.isNotEmpty()) {
        streamsToClose = streams.values.toTypedArray()
        streams.clear()
      }
    }

    streamsToClose?.forEach { stream ->
      ignoreIoExceptions {
        stream.close(streamCode, cause)
      }
    }

    // Close the writer to release its resources (such as deflaters).
    ignoreIoExceptions {
      writer.close()
    }

    // Close the socket to break out the reader thread, which will clean up after itself.
    ignoreIoExceptions {
      socket.close()
    }

    // Release the threads.
    writerExecutor.shutdown()
    pushExecutor.shutdown()
  }

  private fun failConnection(e: IOException?) {
    close(ErrorCode.PROTOCOL_ERROR, ErrorCode.PROTOCOL_ERROR, e)
  }

  /**
   * Sends any initial frames and starts reading frames from the remote peer. This should be called
   * after [Builder.build] for all new connections.
   *
   * @param sendConnectionPreface true to send connection preface frames. This should always be true
   *     except for in tests that don't check for a connection preface.
   */
  @Throws(IOException::class) @JvmOverloads
  fun start(sendConnectionPreface: Boolean = true) {
    if (sendConnectionPreface) {
      writer.connectionPreface()
      writer.settings(okHttpSettings)
      val windowSize = okHttpSettings.initialWindowSize
      if (windowSize != DEFAULT_INITIAL_WINDOW_SIZE) {
        writer.windowUpdate(0, (windowSize - DEFAULT_INITIAL_WINDOW_SIZE).toLong())
      }
    }
    Thread(readerRunnable, "OkHttp $connectionName").start() // Not a daemon thread.
  }

  /** Merges [settings] into this peer's settings and sends them to the remote peer.  */
  @Throws(IOException::class)
  fun setSettings(settings: Settings) {
    synchronized(writer) {
      synchronized(this) {
        if (isShutdown) {
          throw ConnectionShutdownException()
        }
        okHttpSettings.merge(settings)
      }
      writer.settings(settings)
    }
  }

  class Builder(
    /** True if this peer initiated the connection; false if this peer accepted the connection. */
    internal var client: Boolean
  ) {
    internal lateinit var socket: Socket
    internal lateinit var connectionName: String
    internal lateinit var source: BufferedSource
    internal lateinit var sink: BufferedSink
    internal var listener = Listener.REFUSE_INCOMING_STREAMS
    internal var pushObserver = PushObserver.CANCEL
    internal var pingIntervalMillis: Int = 0

    @Throws(IOException::class) @JvmOverloads
    fun socket(
      socket: Socket,
      connectionName: String = socket.connectionName(),
      source: BufferedSource = socket.source().buffer(),
      sink: BufferedSink = socket.sink().buffer()
    ): Builder {
      this.socket = socket
      this.connectionName = connectionName
      this.source = source
      this.sink = sink
      return this
    }

    fun listener(listener: Listener): Builder {
      this.listener = listener
      return this
    }

    fun pushObserver(pushObserver: PushObserver): Builder {
      this.pushObserver = pushObserver
      return this
    }

    fun pingIntervalMillis(pingIntervalMillis: Int): Builder {
      this.pingIntervalMillis = pingIntervalMillis
      return this
    }

    fun build(): Http2Connection {
      return Http2Connection(this)
    }
  }

  /**
   * Methods in this class must not lock FrameWriter. If a method needs to write a frame, create an
   * async task to do so.
   */
  inner class ReaderRunnable internal constructor(
    internal val reader: Http2Reader
  ) : Runnable, Http2Reader.Handler {
    override fun run() {
      var connectionErrorCode = ErrorCode.INTERNAL_ERROR
      var streamErrorCode = ErrorCode.INTERNAL_ERROR
      var errorException: IOException? = null
      try {
        reader.readConnectionPreface(this)
        while (reader.nextFrame(false, this)) {
        }
        connectionErrorCode = ErrorCode.NO_ERROR
        streamErrorCode = ErrorCode.CANCEL
      } catch (e: IOException) {
        errorException = e
        connectionErrorCode = ErrorCode.PROTOCOL_ERROR
        streamErrorCode = ErrorCode.PROTOCOL_ERROR
      } finally {
        close(connectionErrorCode, streamErrorCode, errorException)
        Util.closeQuietly(reader)
      }
    }

    @Throws(IOException::class)
    override fun data(
      inFinished: Boolean,
      streamId: Int,
      source: BufferedSource,
      length: Int
    ) {
      if (pushedStream(streamId)) {
        pushDataLater(streamId, source, length, inFinished)
        return
      }
      val dataStream = getStream(streamId)
      if (dataStream == null) {
        writeSynResetLater(streamId, ErrorCode.PROTOCOL_ERROR)
        updateConnectionFlowControl(length.toLong())
        source.skip(length.toLong())
        return
      }
      dataStream.receiveData(source, length)
      if (inFinished) {
        dataStream.receiveHeaders(Util.EMPTY_HEADERS, true)
      }
    }

    override fun headers(
      inFinished: Boolean,
      streamId: Int,
      associatedStreamId: Int,
      headerBlock: List<Header>
    ) {
      if (pushedStream(streamId)) {
        pushHeadersLater(streamId, headerBlock, inFinished)
        return
      }
      val stream: Http2Stream?
      synchronized(this@Http2Connection) {
        stream = getStream(streamId)

        if (stream == null) {
          // If we're shutdown, don't bother with this stream.
          if (isShutdown) return

          // If the stream ID is less than the last created ID, assume it's already closed.
          if (streamId <= lastGoodStreamId) return

          // If the stream ID is in the client's namespace, assume it's already closed.
          if (streamId % 2 == nextStreamId % 2) return

          // Create a stream.
          val headers = Util.toHeaders(headerBlock)
          val newStream = Http2Stream(streamId, this@Http2Connection, false, inFinished, headers)
          lastGoodStreamId = streamId
          streams[streamId] = newStream
          listenerExecutor.execute("OkHttp $connectionName stream $streamId") {
            try {
              listener.onStream(newStream)
            } catch (e: IOException) {
              Platform.get().log(INFO, "Http2Connection.Listener failure for $connectionName", e)
              ignoreIoExceptions {
                newStream.close(ErrorCode.PROTOCOL_ERROR, e)
              }
            }
          }
          return
        }
      }

      // Update an existing stream.
      stream!!.receiveHeaders(Util.toHeaders(headerBlock), inFinished)
    }

    override fun rstStream(streamId: Int, errorCode: ErrorCode) {
      if (pushedStream(streamId)) {
        pushResetLater(streamId, errorCode)
        return
      }
      val rstStream = removeStream(streamId)
      rstStream?.receiveRstStream(errorCode)
    }

    override fun settings(clearPrevious: Boolean, settings: Settings) {
      var delta = 0L
      var streamsToNotify: Array<Http2Stream>? = null
      synchronized(this@Http2Connection) {
        val priorWriteWindowSize = peerSettings.initialWindowSize
        if (clearPrevious) peerSettings.clear()
        peerSettings.merge(settings)
        applyAndAckSettings(settings)
        val peerInitialWindowSize = peerSettings.initialWindowSize
        if (peerInitialWindowSize != -1 && peerInitialWindowSize != priorWriteWindowSize) {
          delta = (peerInitialWindowSize - priorWriteWindowSize).toLong()
          if (!receivedInitialPeerSettings) {
            receivedInitialPeerSettings = true
          }
          if (streams.isNotEmpty()) {
            streamsToNotify = streams.values.toTypedArray()
          }
        }
        listenerExecutor.execute("OkHttp $connectionName settings") {
          listener.onSettings(this@Http2Connection)
        }
      }
      if (streamsToNotify != null && delta != 0L) {
        for (stream in streamsToNotify!!) {
          synchronized(stream) {
            stream.addBytesToWriteWindow(delta)
          }
        }
      }
    }

    private fun applyAndAckSettings(peerSettings: Settings) {
      writerExecutor.tryExecute("OkHttp $connectionName ACK Settings") {
        try {
          writer.applyAndAckSettings(peerSettings)
        } catch (e: IOException) {
          failConnection(e)
        }
      }
    }

    override fun ackSettings() {
      // TODO: If we don't get this callback after sending settings to the peer, SETTINGS_TIMEOUT.
    }

    override fun ping(
      reply: Boolean,
      payload1: Int,
      payload2: Int
    ) {
      if (reply) {
        synchronized(this@Http2Connection) {
          awaitingPong = false
          (this@Http2Connection as Object).notifyAll()
        }
      } else {
        // Send a reply to a client ping if this is a server and vice versa.
        writerExecutor.tryExecute("OkHttp $connectionName ping") {
          writePing(true, payload1, payload2)
        }
      }
    }

    override fun goAway(
      lastGoodStreamId: Int,
      errorCode: ErrorCode,
      debugData: ByteString
    ) {
      if (debugData.size > 0) {
        // TODO: log the debugData
      }

      // Copy the streams first. We don't want to hold a lock when we call receiveRstStream().
      val streamsCopy: Array<Http2Stream>
      synchronized(this@Http2Connection) {
        streamsCopy = streams.values.toTypedArray()
        isShutdown = true
      }

      // Fail all streams created after the last good stream ID.
      for (http2Stream in streamsCopy) {
        if (http2Stream.id > lastGoodStreamId && http2Stream.isLocallyInitiated) {
          http2Stream.receiveRstStream(REFUSED_STREAM)
          removeStream(http2Stream.id)
        }
      }
    }

    override fun windowUpdate(streamId: Int, windowSizeIncrement: Long) {
      if (streamId == 0) {
        synchronized(this@Http2Connection) {
          bytesLeftInWriteWindow += windowSizeIncrement
          (this@Http2Connection as Object).notifyAll()
        }
      } else {
        val stream = getStream(streamId)
        if (stream != null) {
          synchronized(stream) {
            stream.addBytesToWriteWindow(windowSizeIncrement)
          }
        }
      }
    }

    override fun priority(
      streamId: Int,
      streamDependency: Int,
      weight: Int,
      exclusive: Boolean
    ) {
      // TODO: honor priority.
    }

    override fun pushPromise(
      streamId: Int,
      promisedStreamId: Int,
      requestHeaders: List<Header>
    ) {
      pushRequestLater(promisedStreamId, requestHeaders)
    }

    override fun alternateService(
      streamId: Int,
      origin: String,
      protocol: ByteString,
      host: String,
      port: Int,
      maxAge: Long
    ) {
      // TODO: register alternate service.
    }
  }

  /** Even, positive numbered streams are pushed streams in HTTP/2.  */
  internal fun pushedStream(streamId: Int): Boolean = streamId != 0 && streamId and 1 == 0

  internal fun pushRequestLater(streamId: Int, requestHeaders: List<Header>) {
    synchronized(this) {
      if (currentPushRequests.contains(streamId)) {
        writeSynResetLater(streamId, ErrorCode.PROTOCOL_ERROR)
        return
      }
      currentPushRequests.add(streamId)
    }
    if (!isShutdown) {
      pushExecutor.tryExecute("OkHttp $connectionName Push Request[$streamId]") {
        val cancel = pushObserver.onRequest(streamId, requestHeaders)
        ignoreIoExceptions {
          if (cancel) {
            writer.rstStream(streamId, ErrorCode.CANCEL)
            synchronized(this@Http2Connection) {
              currentPushRequests.remove(streamId)
            }
          }
        }
      }
    }
  }

  internal fun pushHeadersLater(
    streamId: Int,
    requestHeaders: List<Header>,
    inFinished: Boolean
  ) {
    if (!isShutdown) {
      pushExecutor.tryExecute("OkHttp $connectionName Push Headers[$streamId]") {
        val cancel = pushObserver.onHeaders(streamId, requestHeaders, inFinished)
        ignoreIoExceptions {
          if (cancel) writer.rstStream(streamId, ErrorCode.CANCEL)
          if (cancel || inFinished) {
            synchronized(this@Http2Connection) {
              currentPushRequests.remove(streamId)
            }
          }
        }
      }
    }
  }

  /**
   * Eagerly reads `byteCount` bytes from the source before launching a background task to
   * process the data.  This avoids corrupting the stream.
   */
  @Throws(IOException::class)
  internal fun pushDataLater(
    streamId: Int,
    source: BufferedSource,
    byteCount: Int,
    inFinished: Boolean
  ) {
    val buffer = Buffer()
    source.require(byteCount.toLong()) // Eagerly read the frame before firing client thread.
    source.read(buffer, byteCount.toLong())
    if (!isShutdown) {
      pushExecutor.execute("OkHttp $connectionName Push Data[$streamId]") {
        ignoreIoExceptions {
          val cancel = pushObserver.onData(streamId, buffer, byteCount, inFinished)
          if (cancel) writer.rstStream(streamId, ErrorCode.CANCEL)
          if (cancel || inFinished) {
            synchronized(this@Http2Connection) {
              currentPushRequests.remove(streamId)
            }
          }
        }
      }
    }
  }

  internal fun pushResetLater(streamId: Int, errorCode: ErrorCode) {
    if (!isShutdown) {
      pushExecutor.execute("OkHttp $connectionName Push Reset[$streamId]") {
        pushObserver.onReset(streamId, errorCode)
        synchronized(this@Http2Connection) {
          currentPushRequests.remove(streamId)
        }
      }
    }
  }

  /** Listener of streams and settings initiated by the peer.  */
  abstract class Listener {
    /**
     * Handle a new stream from this connection's peer. Implementations should respond by either
     * [replying to the stream][Http2Stream.writeHeaders] or [closing it][Http2Stream.close]. This
     * response does not need to be synchronous.
     */
    @Throws(IOException::class)
    abstract fun onStream(stream: Http2Stream)

    /**
     * Notification that the connection's peer's settings may have changed. Implementations should
     * take appropriate action to handle the updated settings.
     *
     * It is the implementation's responsibility to handle concurrent calls to this method. A remote
     * peer that sends multiple settings frames will trigger multiple calls to this method, and
     * those calls are not necessarily serialized.
     */
    open fun onSettings(connection: Http2Connection) {}

    companion object {
      @JvmField
      val REFUSE_INCOMING_STREAMS: Listener = object : Listener() {
        @Throws(IOException::class)
        override fun onStream(stream: Http2Stream) {
          stream.close(REFUSED_STREAM, null)
        }
      }
    }
  }

  companion object {
    const val OKHTTP_CLIENT_WINDOW_SIZE = 16 * 1024 * 1024

    /**
     * Shared executor to send notifications of incoming streams. This executor requires multiple
     * threads because listeners are not required to return promptly.
     */
    private val listenerExecutor = ThreadPoolExecutor(0,
        Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, SynchronousQueue(),
        Util.threadFactory("OkHttp Http2Connection", true))
  }
}
