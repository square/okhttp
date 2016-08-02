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
package okhttp3.internal.cache2;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import okio.Buffer;
import okio.ByteString;
import okio.Source;
import okio.Timeout;

import static okhttp3.internal.Util.closeQuietly;

/**
 * Replicates a single upstream source into multiple downstream sources. Each downstream source
 * returns the same bytes as the upstream source. Downstream sources may read data either as it
 * is returned by upstream, or after the upstream source has been exhausted.
 *
 * <p>As bytes are returned from upstream they are written to a local file. Downstream sources read
 * from this file as necessary.
 *
 * <p>This class also keeps a small buffer of bytes recently read from upstream. This is intended to
 * save a small amount of file I/O and data copying.
 */
// TODO(jwilson): what to do about timeouts? They could be different and unfortunately when any
//     timeout is hit we like to tear down the whole stream.
final class Relay {
  private static final int SOURCE_UPSTREAM = 1;
  private static final int SOURCE_FILE = 2;

  static final ByteString PREFIX_CLEAN = ByteString.encodeUtf8("OkHttp cache v1\n");
  static final ByteString PREFIX_DIRTY = ByteString.encodeUtf8("OkHttp DIRTY :(\n");
  private static final long FILE_HEADER_SIZE = 32L;

  /**
   * Read/write persistence of the upstream source and its metadata. Its layout is as follows:
   *
   * <ul>
   *   <li>16 bytes: either {@code OkHttp cache v1\n} if the persisted file is complete. This is
   *       another sequence of bytes if the file is incomplete and should not be used.
   *   <li>8 bytes: <i>n</i>: upstream data size
   *   <li>8 bytes: <i>m</i>: metadata size
   *   <li><i>n</i> bytes: upstream data
   *   <li><i>m</i> bytes: metadata
   * </ul>
   *
   * <p>This is closed and assigned to null when the last source is closed and no further sources
   * are permitted.
   */
  private RandomAccessFile file;

  /** The thread that currently has access to upstream. Possibly null. Guarded by this. */
  private Thread upstreamReader;

  /**
   * Null once the file has a complete copy of the upstream bytes. Only the {@code upstreamReader}
   * thread may access this source.
   */
  private Source upstream;

  /**
   * A buffer for {@code upstreamReader} to use when pulling bytes from upstream. Only the {@code
   * upstreamReader} thread may access this buffer.
   */
  private final Buffer upstreamBuffer = new Buffer();

  /** The number of bytes consumed from {@link #upstream}. Guarded by this. */
  private long upstreamPos;

  /** True if there are no further bytes to read from {@code upstream}. Guarded by this. */
  private boolean complete;

  /** User-supplied additional data persisted with the source data. */
  private final ByteString metadata;

  /**
   * The most recently read bytes from {@link #upstream}. This is a suffix of {@link #file}. Guarded
   * by this.
   */
  private final Buffer buffer = new Buffer();

  /** The maximum size of {@code buffer}. */
  private final long bufferMaxSize;

  /**
   * Reference count of the number of active sources reading this stream. When decremented to 0
   * resources are released and all following calls to {@link #newSource} return null. Guarded by
   * this.
   */
  private int sourceCount;

  private Relay(RandomAccessFile file, Source upstream, long upstreamPos, ByteString metadata,
      long bufferMaxSize) {
    this.file = file;
    this.upstream = upstream;
    this.complete = upstream == null;
    this.upstreamPos = upstreamPos;
    this.metadata = metadata;
    this.bufferMaxSize = bufferMaxSize;
  }

  /**
   * Creates a new relay that reads a live stream from {@code upstream}, using {@code file} to share
   * that data with other sources.
   *
   * <p><strong>Warning:</strong> callers to this method must immediately call {@link #newSource} to
   * create a source and close that when they're done. Otherwise a handle to {@code file} will be
   * leaked.
   */
  public static Relay edit(
      File file, Source upstream, ByteString metadata, long bufferMaxSize) throws IOException {
    RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
    Relay result = new Relay(randomAccessFile, upstream, 0L, metadata, bufferMaxSize);

    // Write a dirty header. That way if we crash we won't attempt to recover this.
    randomAccessFile.setLength(0L);
    result.writeHeader(PREFIX_DIRTY, -1L, -1L);

    return result;
  }

  /**
   * Creates a relay that reads a recorded stream from {@code file}.
   *
   * <p><strong>Warning:</strong> callers to this method must immediately call {@link #newSource} to
   * create a source and close that when they're done. Otherwise a handle to {@code file} will be
   * leaked.
   */
  public static Relay read(File file) throws IOException {
    RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
    FileOperator fileOperator = new FileOperator(randomAccessFile.getChannel());

    // Read the header.
    Buffer header = new Buffer();
    fileOperator.read(0, header, FILE_HEADER_SIZE);
    ByteString prefix = header.readByteString(PREFIX_CLEAN.size());
    if (!prefix.equals(PREFIX_CLEAN)) throw new IOException("unreadable cache file");
    long upstreamSize = header.readLong();
    long metadataSize = header.readLong();

    // Read the metadata.
    Buffer metadataBuffer = new Buffer();
    fileOperator.read(FILE_HEADER_SIZE + upstreamSize, metadataBuffer, metadataSize);
    ByteString metadata = metadataBuffer.readByteString();

    // Return the result.
    return new Relay(randomAccessFile, null, upstreamSize, metadata, 0L);
  }

  private void writeHeader(
      ByteString prefix, long upstreamSize, long metadataSize) throws IOException {
    Buffer header = new Buffer();
    header.write(prefix);
    header.writeLong(upstreamSize);
    header.writeLong(metadataSize);
    if (header.size() != FILE_HEADER_SIZE) throw new IllegalArgumentException();

    FileOperator fileOperator = new FileOperator(file.getChannel());
    fileOperator.write(0, header, FILE_HEADER_SIZE);
  }

  private void writeMetadata(long upstreamSize) throws IOException {
    Buffer metadataBuffer = new Buffer();
    metadataBuffer.write(metadata);

    FileOperator fileOperator = new FileOperator(file.getChannel());
    fileOperator.write(FILE_HEADER_SIZE + upstreamSize, metadataBuffer, metadata.size());
  }

  void commit(long upstreamSize) throws IOException {
    // Write metadata to the end of the file.
    writeMetadata(upstreamSize);
    file.getChannel().force(false);

    // Once everything else is in place we can swap the dirty header for a clean one.
    writeHeader(PREFIX_CLEAN, upstreamSize, metadata.size());
    file.getChannel().force(false);

    // This file is complete.
    synchronized (Relay.this) {
      complete = true;
    }

    closeQuietly(upstream);
    upstream = null;
  }

  boolean isClosed() {
    return file == null;
  }

  public ByteString metadata() {
    return metadata;
  }

  /**
   * Returns a new source that returns the same bytes as upstream. Returns null if this relay has
   * been closed and no further sources are possible. In that case callers should retry after
   * building a new relay with {@link #read}.
   */
  public Source newSource() {
    synchronized (Relay.this) {
      if (file == null) return null;
      sourceCount++;
    }

    return new RelaySource();
  }

  class RelaySource implements Source {
    private final Timeout timeout = new Timeout();

    /** The operator to read and write the shared file. Null if this source is closed. */
    private FileOperator fileOperator = new FileOperator(file.getChannel());

    /** The next byte to read. This is always less than or equal to {@code upstreamPos}. */
    private long sourcePos;

    /**
     * Selects where to find the bytes for a read and read them. This is one of three sources.
     *
     * <h3>Upstream:</h3>
     * In this case the current thread is assigned as the upstream reader. We read bytes from
     * upstream and copy them to both the file and to the buffer. Finally we release the upstream
     * reader lock and return the new bytes.
     *
     * <h3>The file</h3>
     * In this case we copy bytes from the file to the {@code sink}.
     *
     * <h3>The buffer</h3>
     * In this case the bytes are immediately copied into {@code sink} and the number of bytes
     * copied is returned.
     *
     * <p>If upstream would be selected but another thread is already reading upstream this will
     * block until that read completes. It is possible to time out while waiting for that.
     */
    @Override public long read(Buffer sink, long byteCount) throws IOException {
      if (fileOperator == null) throw new IllegalStateException("closed");

      long upstreamPos;
      int source;

      selectSource:
      synchronized (Relay.this) {
        // We need new data from upstream.
        while (sourcePos == (upstreamPos = Relay.this.upstreamPos)) {
          // No more data upstream. We're done.
          if (complete) return -1L;

          // Another thread is already reading. Wait for that.
          if (upstreamReader != null) {
            timeout.waitUntilNotified(Relay.this);
            continue;
          }

          // We will do the read.
          upstreamReader = Thread.currentThread();
          source = SOURCE_UPSTREAM;
          break selectSource;
        }

        long bufferPos = upstreamPos - buffer.size();

        // Bytes of the read precede the buffer. Read from the file.
        if (sourcePos < bufferPos) {
          source = SOURCE_FILE;
          break selectSource;
        }

        // The buffer has the data we need. Read from there and return immediately.
        long bytesToRead = Math.min(byteCount, upstreamPos - sourcePos);
        buffer.copyTo(sink, sourcePos - bufferPos, bytesToRead);
        sourcePos += bytesToRead;
        return bytesToRead;
      }

      // Read from the file.
      if (source == SOURCE_FILE) {
        long bytesToRead = Math.min(byteCount, upstreamPos - sourcePos);
        fileOperator.read(FILE_HEADER_SIZE + sourcePos, sink, bytesToRead);
        sourcePos += bytesToRead;
        return bytesToRead;
      }

      // Read from upstream. This always reads a full buffer: that might be more than what the
      // current call to Source.read() has requested.
      try {
        long upstreamBytesRead = upstream.read(upstreamBuffer, bufferMaxSize);

        // If we've exhausted upstream, we're done.
        if (upstreamBytesRead == -1L) {
          commit(upstreamPos);
          return -1L;
        }

        // Update this source and prepare this call's result.
        long bytesRead = Math.min(upstreamBytesRead, byteCount);
        upstreamBuffer.copyTo(sink, 0, bytesRead);
        sourcePos += bytesRead;

        // Append the upstream bytes to the file.
        fileOperator.write(
            FILE_HEADER_SIZE + upstreamPos, upstreamBuffer.clone(), upstreamBytesRead);

        synchronized (Relay.this) {
          // Append new upstream bytes into the buffer. Trim it to its max size.
          buffer.write(upstreamBuffer, upstreamBytesRead);
          if (buffer.size() > bufferMaxSize) {
            buffer.skip(buffer.size() - bufferMaxSize);
          }

          // Now that the file and buffer have bytes, adjust upstreamPos.
          Relay.this.upstreamPos += upstreamBytesRead;
        }

        return bytesRead;
      } finally {
        synchronized (Relay.this) {
          upstreamReader = null;
          Relay.this.notifyAll();
        }
      }
    }

    @Override public Timeout timeout() {
      return timeout;
    }

    @Override public void close() throws IOException {
      if (fileOperator == null) return; // Already closed.
      fileOperator = null;

      RandomAccessFile fileToClose = null;
      synchronized (Relay.this) {
        sourceCount--;
        if (sourceCount == 0) {
          fileToClose = file;
          file = null;
        }
      }

      if (fileToClose != null) {
        closeQuietly(fileToClose);
      }
    }
  }
}
