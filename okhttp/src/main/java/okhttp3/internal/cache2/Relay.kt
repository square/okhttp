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
package okhttp3.internal.cache2

import okhttp3.internal.closeQuietly
import okhttp3.internal.notifyAll
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.Source
import okio.Timeout
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Replicates a single upstream source into multiple downstream sources. Each downstream source
 * returns the same bytes as the upstream source. Downstream sources may read data either as it
 * is returned by upstream, or after the upstream source has been exhausted.
 *
 * As bytes are returned from upstream they are written to a local file. Downstream sources read
 * from this file as necessary.
 *
 * This class also keeps a small buffer of bytes recently read from upstream. This is intended to
 * save a small amount of file I/O and data copying.
 */
class Relay private constructor(
  /**
   * Read/write persistence of the upstream source and its metadata. Its layout is as follows:
   *
   *  * 16 bytes: either `OkHttp cache v1\n` if the persisted file is complete. This is another
   *    sequence of bytes if the file is incomplete and should not be used.
   *  * 8 bytes: *n*: upstream data size
   *  * 8 bytes: *m*: metadata size
   *  * *n* bytes: upstream data
   *  * *m* bytes: metadata
   *
   * This is closed and assigned to null when the last source is closed and no further sources
   * are permitted.
   */
  var file: RandomAccessFile?,

  /**
   * Null once the file has a complete copy of the upstream bytes. Only the [upstreamReader] thread
   * may access this source.
   */
  var upstream: Source?,

  /** The number of bytes consumed from [upstream]. Guarded by this. */
  var upstreamPos: Long,

  /** User-supplied additional data persisted with the source data. */
  private val metadata: ByteString,

  /** The maximum size of [buffer]. */
  val bufferMaxSize: Long
) {
  /** The thread that currently has access to upstream. Possibly null. Guarded by this. */
  var upstreamReader: Thread? = null

  /**
   * A buffer for [upstreamReader] to use when pulling bytes from upstream. Only the
   * [upstreamReader] thread may access this buffer.
   */
  val upstreamBuffer = Buffer()

  /** True if there are no further bytes to read from [upstream]. Guarded by this. */
  var complete = (upstream == null)

  /** The most recently read bytes from [upstream]. This is a suffix of [file]. Guarded by this. */
  val buffer = Buffer()

  /**
   * Reference count of the number of active sources reading this stream. When decremented to 0
   * resources are released and all following calls to [.newSource] return null. Guarded by this.
   */
  var sourceCount = 0

  val isClosed: Boolean
    get() = file == null

  @Throws(IOException::class)
  private fun writeHeader(
    prefix: ByteString,
    upstreamSize: Long,
    metadataSize: Long
  ) {
    val header = Buffer().apply {
      write(prefix)
      writeLong(upstreamSize)
      writeLong(metadataSize)
      require(size == FILE_HEADER_SIZE)
    }

    val fileOperator = FileOperator(file!!.channel)
    fileOperator.write(0, header, FILE_HEADER_SIZE)
  }

  @Throws(IOException::class)
  private fun writeMetadata(upstreamSize: Long) {
    val metadataBuffer = Buffer()
    metadataBuffer.write(metadata)

    val fileOperator = FileOperator(file!!.channel)
    fileOperator.write(FILE_HEADER_SIZE + upstreamSize, metadataBuffer, metadata.size.toLong())
  }

  @Throws(IOException::class)
  fun commit(upstreamSize: Long) {
    // Write metadata to the end of the file.
    writeMetadata(upstreamSize)
    file!!.channel.force(false)

    // Once everything else is in place we can swap the dirty header for a clean one.
    writeHeader(PREFIX_CLEAN, upstreamSize, metadata.size.toLong())
    file!!.channel.force(false)

    // This file is complete.
    synchronized(this@Relay) {
      complete = true
    }

    upstream?.closeQuietly()
    upstream = null
  }

  fun metadata(): ByteString = metadata

  /**
   * Returns a new source that returns the same bytes as upstream. Returns null if this relay has
   * been closed and no further sources are possible. In that case callers should retry after
   * building a new relay with [.read].
   */
  fun newSource(): Source? {
    synchronized(this@Relay) {
      if (file == null) return null
      sourceCount++
    }

    return RelaySource()
  }

  internal inner class RelaySource : Source {
    private val timeout = Timeout()

    /** The operator to read and write the shared file. Null if this source is closed. */
    private var fileOperator: FileOperator? = FileOperator(file!!.channel)

    /** The next byte to read. This is always less than or equal to [upstreamPos]. */
    private var sourcePos = 0L

    /**
     * Selects where to find the bytes for a read and read them. This is one of three sources.
     *
     * ## Upstream
     *
     * In this case the current thread is assigned as the upstream reader. We read bytes from
     * upstream and copy them to both the file and to the buffer. Finally we release the upstream
     * reader lock and return the new bytes.
     *
     * ## The file
     *
     * In this case we copy bytes from the file to the [sink].
     *
     * ## The buffer
     *
     * In this case the bytes are immediately copied into [sink] and the number of bytes copied is
     * returned.
     *
     * If upstream would be selected but another thread is already reading upstream this will
     * block until that read completes. It is possible to time out while waiting for that.
     */
    @Throws(IOException::class)
    override fun read(sink: Buffer, byteCount: Long): Long {
      check(fileOperator != null)

      val source: Int = synchronized(this@Relay) {
        // We need new data from upstream.
        while (true) {
          val upstreamPos = this@Relay.upstreamPos
          if (sourcePos != upstreamPos) break

          // No more data upstream. We're done.
          if (complete) return -1L

          // Another thread is already reading. Wait for that.
          if (upstreamReader != null) {
            timeout.waitUntilNotified(this@Relay)
            continue
          }

          // We will do the read.
          upstreamReader = Thread.currentThread()
          return@synchronized SOURCE_UPSTREAM
        }

        val bufferPos = upstreamPos - buffer.size

        // Bytes of the read precede the buffer. Read from the file.
        if (sourcePos < bufferPos) {
          return@synchronized SOURCE_FILE
        }

        // The buffer has the data we need. Read from there and return immediately.
        val bytesToRead = minOf(byteCount, upstreamPos - sourcePos)
        buffer.copyTo(sink, sourcePos - bufferPos, bytesToRead)
        sourcePos += bytesToRead
        return bytesToRead
      }

      // Read from the file.
      if (source == SOURCE_FILE) {
        val bytesToRead = minOf(byteCount, upstreamPos - sourcePos)
        fileOperator!!.read(FILE_HEADER_SIZE + sourcePos, sink, bytesToRead)
        sourcePos += bytesToRead
        return bytesToRead
      }

      // Read from upstream. This always reads a full buffer: that might be more than what the
      // current call to Source.read() has requested.
      try {
        val upstreamBytesRead = upstream!!.read(upstreamBuffer, bufferMaxSize)

        // If we've exhausted upstream, we're done.
        if (upstreamBytesRead == -1L) {
          commit(upstreamPos)
          return -1L
        }

        // Update this source and prepare this call's result.
        val bytesRead = minOf(upstreamBytesRead, byteCount)
        upstreamBuffer.copyTo(sink, 0, bytesRead)
        sourcePos += bytesRead

        // Append the upstream bytes to the file.
        fileOperator!!.write(
            FILE_HEADER_SIZE + upstreamPos, upstreamBuffer.clone(), upstreamBytesRead)

        synchronized(this@Relay) {
          // Append new upstream bytes into the buffer. Trim it to its max size.
          buffer.write(upstreamBuffer, upstreamBytesRead)
          if (buffer.size > bufferMaxSize) {
            buffer.skip(buffer.size - bufferMaxSize)
          }

          // Now that the file and buffer have bytes, adjust upstreamPos.
          this@Relay.upstreamPos += upstreamBytesRead
        }

        return bytesRead
      } finally {
        synchronized(this@Relay) {
          upstreamReader = null
          this@Relay.notifyAll()
        }
      }
    }

    override fun timeout(): Timeout = timeout

    @Throws(IOException::class)
    override fun close() {
      if (fileOperator == null) return // Already closed.
      fileOperator = null

      var fileToClose: RandomAccessFile? = null
      synchronized(this@Relay) {
        sourceCount--
        if (sourceCount == 0) {
          fileToClose = file
          file = null
        }
      }

      fileToClose?.closeQuietly()
    }
  }

  companion object {
    // TODO(jwilson): what to do about timeouts? They could be different and unfortunately when any
    //     timeout is hit we like to tear down the whole stream.

    private const val SOURCE_UPSTREAM = 1
    private const val SOURCE_FILE = 2

    @JvmField val PREFIX_CLEAN = "OkHttp cache v1\n".encodeUtf8()
    @JvmField val PREFIX_DIRTY = "OkHttp DIRTY :(\n".encodeUtf8()
    private const val FILE_HEADER_SIZE = 32L

    /**
     * Creates a new relay that reads a live stream from [upstream], using [file] to share that data
     * with other sources.
     *
     * **Warning:** callers to this method must immediately call [newSource] to create a source and
     * close that when they're done. Otherwise a handle to [file] will be leaked.
     */
    @Throws(IOException::class)
    fun edit(
      file: File,
      upstream: Source,
      metadata: ByteString,
      bufferMaxSize: Long
    ): Relay {
      val randomAccessFile = RandomAccessFile(file, "rw")
      val result = Relay(randomAccessFile, upstream, 0L, metadata, bufferMaxSize)

      // Write a dirty header. That way if we crash we won't attempt to recover this.
      randomAccessFile.setLength(0L)
      result.writeHeader(PREFIX_DIRTY, -1L, -1L)

      return result
    }

    /**
     * Creates a relay that reads a recorded stream from [file].
     *
     * **Warning:** callers to this method must immediately call [newSource] to create a source and
     * close that when they're done. Otherwise a handle to [file] will be leaked.
     */
    @Throws(IOException::class)
    fun read(file: File): Relay {
      val randomAccessFile = RandomAccessFile(file, "rw")
      val fileOperator = FileOperator(randomAccessFile.channel)

      // Read the header.
      val header = Buffer()
      fileOperator.read(0, header, FILE_HEADER_SIZE)
      val prefix = header.readByteString(PREFIX_CLEAN.size.toLong())
      if (prefix != PREFIX_CLEAN) throw IOException("unreadable cache file")
      val upstreamSize = header.readLong()
      val metadataSize = header.readLong()

      // Read the metadata.
      val metadataBuffer = Buffer()
      fileOperator.read(FILE_HEADER_SIZE + upstreamSize, metadataBuffer, metadataSize)
      val metadata = metadataBuffer.readByteString()

      // Return the result.
      return Relay(randomAccessFile, null, upstreamSize, metadata, 0L)
    }
  }
}
