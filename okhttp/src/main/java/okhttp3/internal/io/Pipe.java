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
package okhttp3.internal.io;

import java.io.IOException;
import java.io.InterruptedIOException;
import okio.Buffer;
import okio.Sink;
import okio.Source;
import okio.Timeout;

/**
 * A source and a sink that are attached. The sink's output is the source's input. Typically each
 * is accessed by its own thread: a producer thread writes data to the sink and a consumer thread
 * reads data from the source.
 *
 * <p>This class uses a buffer to decouple source and sink. This buffer has a user-specified maximum
 * size. When a producer thread outruns its consumer the buffer fills up and eventually writes to
 * the sink will block until the consumer has caught up. Symmetrically, if a consumer outruns its
 * producer reads block until there is data to be read. Limits on the amount of time spent waiting
 * for the other party can be configured with {@linkplain Timeout timeouts} on the source and the
 * sink.
 *
 * <p>When the sink is closed, source reads will continue to complete normally until the buffer has
 * been exhausted. At that point reads will return -1, indicating the end of the stream. But if the
 * source is closed first, writes to the sink will immediately fail with an {@link IOException}.
 */
public final class Pipe {
  final long maxBufferSize;
  final Buffer buffer = new Buffer();
  boolean sinkClosed;
  boolean sourceClosed;
  public final Sink sink = new PipeSink();
  public final Source source = new PipeSource();

  public Pipe(long maxBufferSize) {
    if (maxBufferSize < 1L) {
      throw new IllegalArgumentException("maxBufferSize < 1: " + maxBufferSize);
    }
    this.maxBufferSize = maxBufferSize;
  }

  final class PipeSink implements Sink {
    final Timeout timeout = new Timeout();

    @Override public void write(Buffer source, long byteCount) throws IOException {
      synchronized (buffer) {
        if (sinkClosed) throw new IllegalStateException("closed");

        while (byteCount > 0) {
          if (sourceClosed) throw new IOException("source is closed");

          long bufferSpaceAvailable = maxBufferSize - buffer.size();
          if (bufferSpaceAvailable == 0) {
            waitUntilNotified(buffer, timeout); // Wait until the source drains the buffer.
            continue;
          }

          long bytesToWrite = Math.min(bufferSpaceAvailable, byteCount);
          buffer.write(source, bytesToWrite);
          byteCount -= bytesToWrite;
          buffer.notifyAll(); // Notify the source that it can resume reading.
        }
      }
    }

    @Override public void flush() throws IOException {
      synchronized (buffer) {
        if (sinkClosed) throw new IllegalStateException("closed");

        while (buffer.size() > 0) {
          if (sourceClosed) throw new IOException("source is closed");
          waitUntilNotified(buffer, timeout);
        }
      }
    }

    @Override public void close() throws IOException {
      synchronized (buffer) {
        if (sinkClosed) return;
        try {
          flush();
        } finally {
          sinkClosed = true;
          buffer.notifyAll(); // Notify the source that no more bytes are coming.
        }
      }
    }

    @Override public Timeout timeout() {
      return timeout;
    }
  }

  final class PipeSource implements Source {
    final Timeout timeout = new Timeout();

    @Override public long read(Buffer sink, long byteCount) throws IOException {
      synchronized (buffer) {
        if (sourceClosed) throw new IllegalStateException("closed");

        while (buffer.size() == 0) {
          if (sinkClosed) return -1L;
          waitUntilNotified(buffer, timeout); // Wait until the sink fills the buffer.
        }

        long result = buffer.read(sink, byteCount);
        buffer.notifyAll(); // Notify the sink that it can resume writing.
        return result;
      }
    }

    @Override public void close() throws IOException {
      synchronized (buffer) {
        sourceClosed = true;
        buffer.notifyAll(); // Notify the sink that no more bytes are desired.
      }
    }

    @Override public Timeout timeout() {
      return timeout;
    }
  }

  /**
   * Waits on {@code monitor} until it is notified. Throws {@link InterruptedIOException} if either
   * the thread is interrupted or if {@code timeout} elapses before {@code monitor} is notified. The
   * caller must be synchronized on {@code monitor}.
   */
  static void waitUntilNotified(Object monitor, Timeout timeout) throws IOException {
    try {
      boolean hasDeadline = timeout.hasDeadline();
      long timeoutNanos = timeout.timeoutNanos();

      if (!hasDeadline && timeoutNanos == 0L) {
        monitor.wait(); // There is no timeout: wait forever.
        return;
      }

      // Compute how long we'll wait.
      long waitNanos;
      long start = System.nanoTime();
      if (hasDeadline && timeoutNanos != 0) {
        long deadlineNanos = timeout.deadlineNanoTime() - start;
        waitNanos = Math.min(timeoutNanos, deadlineNanos);
      } else if (hasDeadline) {
        waitNanos = timeout.deadlineNanoTime() - start;
      } else {
        waitNanos = timeoutNanos;
      }

      // Attempt to wait that long. This will break out early if the monitor is notified.
      long elapsedNanos = 0L;
      if (waitNanos > 0L) {
        long waitMillis = waitNanos / 1000000L;
        monitor.wait(waitMillis, (int) (waitNanos - waitMillis * 1000000L));
        elapsedNanos = System.nanoTime() - start;
      }

      // Throw if the timeout elapsed before the monitor was notified.
      if (elapsedNanos >= waitNanos) {
        throw new InterruptedIOException("timeout");
      }
    } catch (InterruptedException e) {
      throw new InterruptedIOException("interrupted");
    }
  }
}

