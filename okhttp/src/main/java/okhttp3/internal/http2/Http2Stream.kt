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

import okhttp3.Headers
import okhttp3.internal.EMPTY_HEADERS
import okhttp3.internal.notifyAll
import okhttp3.internal.toHeaderList
import okhttp3.internal.wait
import okio.AsyncTimeout
import okio.Buffer
import okio.BufferedSource
import okio.Sink
import okio.Source
import okio.Timeout
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.ArrayDeque

/** A logical bidirectional stream. */
@Suppress("NAME_SHADOWING")
class Http2Stream internal constructor(
  val id: Int,
  val connection: Http2Connection,
  outFinished: Boolean,
  inFinished: Boolean,
  headers: Headers?
) {
  // Internal state is guarded by this. No long-running or potentially blocking operations are
  // performed while the lock is held.

  /** The total number of bytes consumed by the application. */
  var readBytesTotal = 0L
    internal set

  /** The total number of bytes acknowledged by outgoing `WINDOW_UPDATE` frames. */
  var readBytesAcknowledged = 0L
    internal set

  /** The total number of bytes produced by the application. */
  var writeBytesTotal = 0L
    internal set

  /** The total number of bytes permitted to be produced by incoming `WINDOW_UPDATE` frame. */
  var writeBytesMaximum: Long = connection.peerSettings.initialWindowSize.toLong()
    internal set

  /** Received headers yet to be [taken][takeHeaders], or [read][FramingSource.read]. */
  private val headersQueue = ArrayDeque<Headers>()

  /** True if response headers have been sent or received. */
  private var hasResponseHeaders: Boolean = false

  internal val source = FramingSource(
      maxByteCount = connection.okHttpSettings.initialWindowSize.toLong(),
      finished = inFinished
  )
  internal val sink = FramingSink(
      finished = outFinished
  )
  internal val readTimeout = StreamTimeout()
  internal val writeTimeout = StreamTimeout()

  /**
   * The reason why this stream was closed, or null if it closed normally or has not yet been
   * closed.
   *
   * If there are multiple reasons to abnormally close this stream (such as both peers closing it
   * near-simultaneously) then this is the first reason known to this peer.
   */
  @get:Synchronized internal var errorCode: ErrorCode? = null

  /** The exception that explains [errorCode]. Null if no exception was provided. */
  internal var errorException: IOException? = null

  init {
    if (headers != null) {
      check(!isLocallyInitiated) { "locally-initiated streams shouldn't have headers yet" }
      headersQueue += headers
    } else {
      check(isLocallyInitiated) { "remotely-initiated streams should have headers" }
    }
  }

  /**
   * Returns true if this stream is open. A stream is open until either:
   *
   *  * A `SYN_RESET` frame abnormally terminates the stream.
   *  * Both input and output streams have transmitted all data and headers.
   *
   * Note that the input stream may continue to yield data even after a stream reports itself as
   * not open. This is because input data is buffered.
   */
  val isOpen: Boolean
    @Synchronized get() {
      if (errorCode != null) {
        return false
      }
      if ((source.finished || source.closed) &&
          (sink.finished || sink.closed) &&
          hasResponseHeaders) {
        return false
      }
      return true
    }

  /** Returns true if this stream was created by this peer. */
  val isLocallyInitiated: Boolean
    get() {
      val streamIsClient = (id and 1) == 1
      return connection.client == streamIsClient
    }

  /**
   * Removes and returns the stream's received response headers, blocking if necessary until headers
   * have been received. If the returned list contains multiple blocks of headers the blocks will be
   * delimited by 'null'.
   */
  @Synchronized @Throws(IOException::class)
  fun takeHeaders(): Headers {
    readTimeout.enter()
    try {
      while (headersQueue.isEmpty() && errorCode == null) {
        waitForIo()
      }
    } finally {
      readTimeout.exitAndThrowIfTimedOut()
    }
    if (headersQueue.isNotEmpty()) {
      return headersQueue.removeFirst()
    }
    throw errorException ?: StreamResetException(errorCode!!)
  }

  /**
   * Returns the trailers. It is only safe to call this once the source stream has been completely
   * exhausted.
   */
  @Synchronized @Throws(IOException::class)
  fun trailers(): Headers {
    if (errorCode != null) {
      throw errorException ?: StreamResetException(errorCode!!)
    }
    check(source.finished && source.receiveBuffer.exhausted() && source.readBuffer.exhausted()) {
      "too early; can't read the trailers yet"
    }
    return source.trailers ?: EMPTY_HEADERS
  }

  /**
   * Sends a reply to an incoming stream.
   *
   * @param outFinished true to eagerly finish the output stream to send data to the remote peer.
   *     Corresponds to `FLAG_FIN`.
   * @param flushHeaders true to force flush the response headers. This should be true unless the
   *     response body exists and will be written immediately.
   */
  @Throws(IOException::class)
  fun writeHeaders(responseHeaders: List<Header>, outFinished: Boolean, flushHeaders: Boolean) {
    var flushHeaders = flushHeaders
    assert(!Thread.holdsLock(this@Http2Stream))
    synchronized(this) {
      this.hasResponseHeaders = true
      if (outFinished) {
        this.sink.finished = true
      }
    }

    // Only DATA frames are subject to flow-control. Transmit the HEADER frame if the connection
    // flow-control window is fully depleted.
    if (!flushHeaders) {
      synchronized(connection) {
        flushHeaders = (connection.writeBytesTotal >= connection.writeBytesMaximum)
      }
    }

    connection.writeHeaders(id, outFinished, responseHeaders)

    if (flushHeaders) {
      connection.flush()
    }
  }

  fun enqueueTrailers(trailers: Headers) {
    synchronized(this) {
      check(!sink.finished) { "already finished" }
      require(trailers.size != 0) { "trailers.size() == 0" }
      this.sink.trailers = trailers
    }
  }

  fun readTimeout(): Timeout = readTimeout

  fun writeTimeout(): Timeout = writeTimeout

  /** Returns a source that reads data from the peer. */
  fun getSource(): Source = source

  /**
   * Returns a sink that can be used to write data to the peer.
   *
   * @throws IllegalStateException if this stream was initiated by the peer and a [writeHeaders] has
   *     not yet been sent.
   */
  fun getSink(): Sink {
    synchronized(this) {
      check(hasResponseHeaders || isLocallyInitiated) {
        "reply before requesting the sink"
      }
    }
    return sink
  }

  /**
   * Abnormally terminate this stream. This blocks until the `RST_STREAM` frame has been
   * transmitted.
   */
  @Throws(IOException::class)
  fun close(rstStatusCode: ErrorCode, errorException: IOException?) {
    if (!closeInternal(rstStatusCode, errorException)) {
      return // Already closed.
    }
    connection.writeSynReset(id, rstStatusCode)
  }

  /**
   * Abnormally terminate this stream. This enqueues a `RST_STREAM` frame and returns immediately.
   */
  fun closeLater(errorCode: ErrorCode) {
    if (!closeInternal(errorCode, null)) {
      return // Already closed.
    }
    connection.writeSynResetLater(id, errorCode)
  }

  /** Returns true if this stream was closed. */
  private fun closeInternal(errorCode: ErrorCode, errorException: IOException?): Boolean {
    assert(!Thread.holdsLock(this))
    synchronized(this) {
      if (this.errorCode != null) {
        return false
      }
      if (source.finished && sink.finished) {
        return false
      }
      this.errorCode = errorCode
      this.errorException = errorException
      notifyAll()
    }
    connection.removeStream(id)
    return true
  }

  @Throws(IOException::class)
  fun receiveData(source: BufferedSource, length: Int) {
    assert(!Thread.holdsLock(this@Http2Stream))
    this.source.receive(source, length.toLong())
  }

  /**
   * Accept headers from the network and store them until the client calls [takeHeaders], or
   * [FramingSource.read] them.
   */
  fun receiveHeaders(headers: Headers, inFinished: Boolean) {
    assert(!Thread.holdsLock(this@Http2Stream))
    val open: Boolean
    synchronized(this) {
      if (!hasResponseHeaders || !inFinished) {
        hasResponseHeaders = true
        headersQueue += headers
      } else {
        this.source.trailers = headers
      }
      if (inFinished) {
        this.source.finished = true
      }
      open = isOpen
      notifyAll()
    }
    if (!open) {
      connection.removeStream(id)
    }
  }

  @Synchronized fun receiveRstStream(errorCode: ErrorCode) {
    if (this.errorCode == null) {
      this.errorCode = errorCode
      notifyAll()
    }
  }

  /**
   * A source that reads the incoming data frames of a stream. Although this class uses
   * synchronization to safely receive incoming data frames, it is not intended for use by multiple
   * readers.
   */
  inner class FramingSource internal constructor(
    /** Maximum number of bytes to buffer before reporting a flow control error. */
    private val maxByteCount: Long,

    /**
     * True if either side has cleanly shut down this stream. We will receive no more bytes beyond
     * those already in the buffer.
     */
    internal var finished: Boolean
  ) : Source {
    /** Buffer to receive data from the network into. Only accessed by the reader thread. */
    val receiveBuffer = Buffer()

    /** Buffer with readable data. Guarded by Http2Stream.this. */
    val readBuffer = Buffer()

    /**
     * Received trailers. Null unless the server has provided trailers. Undefined until the stream
     * is exhausted. Guarded by Http2Stream.this.
     */
    var trailers: Headers? = null

    /** True if the caller has closed this stream. */
    internal var closed: Boolean = false

    @Throws(IOException::class)
    override fun read(sink: Buffer, byteCount: Long): Long {
      require(byteCount >= 0L) { "byteCount < 0: $byteCount" }

      while (true) {
        var tryAgain = false
        var readBytesDelivered = -1L
        var errorExceptionToDeliver: IOException? = null

        // 1. Decide what to do in a synchronized block.

        synchronized(this@Http2Stream) {
          readTimeout.enter()
          try {
            if (errorCode != null) {
              // Prepare to deliver an error.
              errorExceptionToDeliver = errorException ?: StreamResetException(errorCode!!)
            }

            if (closed) {
              throw IOException("stream closed")
            } else if (readBuffer.size > 0L) {
              // Prepare to read bytes. Start by moving them to the caller's buffer.
              readBytesDelivered = readBuffer.read(sink, minOf(byteCount, readBuffer.size))
              readBytesTotal += readBytesDelivered

              val unacknowledgedBytesRead = readBytesTotal - readBytesAcknowledged
              if (errorExceptionToDeliver == null &&
                  unacknowledgedBytesRead >= connection.okHttpSettings.initialWindowSize / 2) {
                // Flow control: notify the peer that we're ready for more data! Only send a
                // WINDOW_UPDATE if the stream isn't in error.
                connection.writeWindowUpdateLater(id, unacknowledgedBytesRead)
                readBytesAcknowledged = readBytesTotal
              }
            } else if (!finished && errorExceptionToDeliver == null) {
              // Nothing to do. Wait until that changes then try again.
              waitForIo()
              tryAgain = true
            }
          } finally {
            readTimeout.exitAndThrowIfTimedOut()
          }
        }

        // 2. Do it outside of the synchronized block and timeout.

        if (tryAgain) {
          continue
        }

        if (readBytesDelivered != -1L) {
          // Update connection.unacknowledgedBytesRead outside the synchronized block.
          updateConnectionFlowControl(readBytesDelivered)
          return readBytesDelivered
        }

        if (errorExceptionToDeliver != null) {
          // We defer throwing the exception until now so that we can refill the connection
          // flow-control window. This is necessary because we don't transmit window updates until
          // the application reads the data. If we throw this prior to updating the connection
          // flow-control window, we risk having it go to 0 preventing the server from sending data.
          throw errorExceptionToDeliver!!
        }

        return -1L // This source is exhausted.
      }
    }

    private fun updateConnectionFlowControl(read: Long) {
      assert(!Thread.holdsLock(this@Http2Stream))
      connection.updateConnectionFlowControl(read)
    }

    /**
     * Accept bytes on the connection's reader thread. This function avoids holding locks while it
     * performs blocking reads for the incoming bytes.
     */
    @Throws(IOException::class)
    internal fun receive(source: BufferedSource, byteCount: Long) {
      var byteCount = byteCount
      assert(!Thread.holdsLock(this@Http2Stream))

      while (byteCount > 0L) {
        val finished: Boolean
        val flowControlError: Boolean
        synchronized(this@Http2Stream) {
          finished = this.finished
          flowControlError = byteCount + readBuffer.size > maxByteCount
        }

        // If the peer sends more data than we can handle, discard it and close the connection.
        if (flowControlError) {
          source.skip(byteCount)
          closeLater(ErrorCode.FLOW_CONTROL_ERROR)
          return
        }

        // Discard data received after the stream is finished. It's probably a benign race.
        if (finished) {
          source.skip(byteCount)
          return
        }

        // Fill the receive buffer without holding any locks.
        val read = source.read(receiveBuffer, byteCount)
        if (read == -1L) throw EOFException()
        byteCount -= read

        // Move the received data to the read buffer to the reader can read it. If this source has
        // been closed since this read began we must discard the incoming data and tell the
        // connection we've done so.
        var bytesDiscarded = 0L
        synchronized(this@Http2Stream) {
          if (closed) {
            bytesDiscarded = receiveBuffer.size
            receiveBuffer.clear()
          } else {
            val wasEmpty = readBuffer.size == 0L
            readBuffer.writeAll(receiveBuffer)
            if (wasEmpty) {
              this@Http2Stream.notifyAll()
            }
          }
        }
        if (bytesDiscarded > 0L) {
          updateConnectionFlowControl(bytesDiscarded)
        }
      }
    }

    override fun timeout(): Timeout = readTimeout

    @Throws(IOException::class)
    override fun close() {
      val bytesDiscarded: Long
      synchronized(this@Http2Stream) {
        closed = true
        bytesDiscarded = readBuffer.size
        readBuffer.clear()
        this@Http2Stream.notifyAll() // TODO(jwilson): Unnecessary?
      }
      if (bytesDiscarded > 0L) {
        updateConnectionFlowControl(bytesDiscarded)
      }
      cancelStreamIfNecessary()
    }
  }

  @Throws(IOException::class)
  internal fun cancelStreamIfNecessary() {
    assert(!Thread.holdsLock(this@Http2Stream))
    val open: Boolean
    val cancel: Boolean
    synchronized(this) {
      cancel = !source.finished && source.closed && (sink.finished || sink.closed)
      open = isOpen
    }
    if (cancel) {
      // RST this stream to prevent additional data from being sent. This is safe because the input
      // stream is closed (we won't use any further bytes) and the output stream is either finished
      // or closed (so RSTing both streams doesn't cause harm).
      this@Http2Stream.close(ErrorCode.CANCEL, null)
    } else if (!open) {
      connection.removeStream(id)
    }
  }

  /** A sink that writes outgoing data frames of a stream. This class is not thread safe. */
  internal inner class FramingSink(
    /** True if either side has cleanly shut down this stream. We shall send no more bytes. */
    var finished: Boolean = false
  ) : Sink {

    /**
     * Buffer of outgoing data. This batches writes of small writes into this sink as larges frames
     * written to the outgoing connection. Batching saves the (small) framing overhead.
     */
    private val sendBuffer = Buffer()

    /** Trailers to send at the end of the stream. */
    var trailers: Headers? = null

    var closed: Boolean = false

    @Throws(IOException::class)
    override fun write(source: Buffer, byteCount: Long) {
      assert(!Thread.holdsLock(this@Http2Stream))
      sendBuffer.write(source, byteCount)
      while (sendBuffer.size >= EMIT_BUFFER_SIZE) {
        emitFrame(false)
      }
    }

    /**
     * Emit a single data frame to the connection. The frame's size be limited by this stream's
     * write window. This method will block until the write window is nonempty.
     */
    @Throws(IOException::class)
    private fun emitFrame(outFinishedOnLastFrame: Boolean) {
      val toWrite: Long
      val outFinished: Boolean
      synchronized(this@Http2Stream) {
        writeTimeout.enter()
        try {
          while (writeBytesTotal >= writeBytesMaximum &&
              !finished &&
              !closed &&
              errorCode == null) {
            waitForIo() // Wait until we receive a WINDOW_UPDATE for this stream.
          }
        } finally {
          writeTimeout.exitAndThrowIfTimedOut()
        }

        checkOutNotClosed() // Kick out if the stream was reset or closed while waiting.
        toWrite = minOf(writeBytesMaximum - writeBytesTotal, sendBuffer.size)
        writeBytesTotal += toWrite
        outFinished = outFinishedOnLastFrame && toWrite == sendBuffer.size && errorCode == null
      }

      writeTimeout.enter()
      try {
        connection.writeData(id, outFinished, sendBuffer, toWrite)
      } finally {
        writeTimeout.exitAndThrowIfTimedOut()
      }
    }

    @Throws(IOException::class)
    override fun flush() {
      assert(!Thread.holdsLock(this@Http2Stream))
      synchronized(this@Http2Stream) {
        checkOutNotClosed()
      }
      while (sendBuffer.size > 0L) {
        emitFrame(false)
        connection.flush()
      }
    }

    override fun timeout(): Timeout = writeTimeout

    @Throws(IOException::class)
    override fun close() {
      assert(!Thread.holdsLock(this@Http2Stream))

      val outFinished: Boolean
      synchronized(this@Http2Stream) {
        if (closed) return
        outFinished = errorCode == null
      }
      if (!sink.finished) {
        // We have 0 or more frames of data, and 0 or more frames of trailers. We need to send at
        // least one frame with the END_STREAM flag set. That must be the last frame, and the
        // trailers must be sent after all of the data.
        val hasData = sendBuffer.size > 0L
        val hasTrailers = trailers != null
        when {
          hasTrailers -> {
            while (sendBuffer.size > 0L) {
              emitFrame(false)
            }
            connection.writeHeaders(id, outFinished, trailers!!.toHeaderList())
          }

          hasData -> {
            while (sendBuffer.size > 0L) {
              emitFrame(true)
            }
          }

          outFinished -> {
            connection.writeData(id, true, null, 0L)
          }
        }
      }
      synchronized(this@Http2Stream) {
        closed = true
      }
      connection.flush()
      cancelStreamIfNecessary()
    }
  }

  companion object {
    internal const val EMIT_BUFFER_SIZE = 16384L
  }

  /** [delta] will be negative if a settings frame initial window is smaller than the last. */
  fun addBytesToWriteWindow(delta: Long) {
    writeBytesMaximum += delta
    if (delta > 0L) {
      this@Http2Stream.notifyAll()
    }
  }

  @Throws(IOException::class)
  internal fun checkOutNotClosed() {
    when {
      sink.closed -> throw IOException("stream closed")
      sink.finished -> throw IOException("stream finished")
      errorCode != null -> throw errorException ?: StreamResetException(errorCode!!)
    }
  }

  /**
   * Like [Object.wait], but throws an [InterruptedIOException] when interrupted instead of the more
   * awkward [InterruptedException].
   */
  @Throws(InterruptedIOException::class)
  internal fun waitForIo() {
    try {
      wait()
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt() // Retain interrupted status.
      throw InterruptedIOException()
    }
  }

  /**
   * The Okio timeout watchdog will call [timedOut] if the timeout is reached. In that case we close
   * the stream (asynchronously) which will notify the waiting thread.
   */
  internal inner class StreamTimeout : AsyncTimeout() {
    override fun timedOut() {
      closeLater(ErrorCode.CANCEL)
    }

    override fun newTimeoutException(cause: IOException?): IOException {
      return SocketTimeoutException("timeout").apply {
        if (cause != null) {
          initCause(cause)
        }
      }
    }

    @Throws(IOException::class)
    fun exitAndThrowIfTimedOut() {
      if (exit()) throw newTimeoutException(null)
    }
  }
}
