/*
 * Copyright (C) 2013 Square, Inc.
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
package com.squareup.okhttp.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static com.squareup.okhttp.internal.Util.checkOffsetAndCount;

/**
 * An output stream wrapper that recovers from failures in the underlying stream
 * by replacing it with another stream. This class buffers a fixed amount of
 * data under the assumption that failures occur early in a stream's life.
 * If a failure occurs after the buffer has been exhausted, no recovery is
 * attempted.
 *
 * <p>Subclasses must override {@link #replacementStream} which will request a
 * replacement stream each time an {@link IOException} is encountered on the
 * current stream.
 */
public abstract class FaultRecoveringOutputStream extends AbstractOutputStream {
  private final int maxReplayBufferLength;

  /** Bytes to transmit on the replacement stream, or null if no recovery is possible. */
  private ByteArrayOutputStream replayBuffer;
  private OutputStream out;

  /**
   * @param maxReplayBufferLength the maximum number of successfully written
   *     bytes to buffer so they can be replayed in the event of an error.
   *     Failure recoveries are not possible once this limit has been exceeded.
   */
  public FaultRecoveringOutputStream(int maxReplayBufferLength, OutputStream out) {
    if (maxReplayBufferLength < 0) throw new IllegalArgumentException();
    this.maxReplayBufferLength = maxReplayBufferLength;
    this.replayBuffer = new ByteArrayOutputStream(maxReplayBufferLength);
    this.out = out;
  }

  @Override public final void write(byte[] buffer, int offset, int count) throws IOException {
    if (closed) throw new IOException("stream closed");
    checkOffsetAndCount(buffer.length, offset, count);

    while (true) {
      try {
        out.write(buffer, offset, count);

        if (replayBuffer != null) {
          if (count + replayBuffer.size() > maxReplayBufferLength) {
            // Failure recovery is no longer possible once we overflow the replay buffer.
            replayBuffer = null;
          } else {
            // Remember the written bytes to the replay buffer.
            replayBuffer.write(buffer, offset, count);
          }
        }
        return;
      } catch (IOException e) {
        if (!recover(e)) throw e;
      }
    }
  }

  @Override public final void flush() throws IOException {
    if (closed) {
      return; // don't throw; this stream might have been closed on the caller's behalf
    }
    while (true) {
      try {
        out.flush();
        return;
      } catch (IOException e) {
        if (!recover(e)) throw e;
      }
    }
  }

  @Override public final void close() throws IOException {
    if (closed) {
      return;
    }
    while (true) {
      try {
        out.close();
        closed = true;
        return;
      } catch (IOException e) {
        if (!recover(e)) throw e;
      }
    }
  }

  /**
   * Attempt to replace {@code out} with another equivalent stream. Returns true
   * if a suitable replacement stream was found.
   */
  private boolean recover(IOException e) {
    if (replayBuffer == null) {
      return false; // Can't recover because we've dropped data that we would need to replay.
    }

    while (true) {
      OutputStream replacementStream = null;
      try {
        replacementStream = replacementStream(e);
        if (replacementStream == null) {
          return false;
        }
        replaceStream(replacementStream);
        return true;
      } catch (IOException replacementStreamFailure) {
        // The replacement was also broken. Loop to ask for another replacement.
        Util.closeQuietly(replacementStream);
        e = replacementStreamFailure;
      }
    }
  }

  /**
   * Returns true if errors in the underlying stream can currently be recovered.
   */
  public boolean isRecoverable() {
    return replayBuffer != null;
  }

  /**
   * Replaces the current output stream with {@code replacementStream}, writing
   * any replay bytes to it if they exist. The current output stream is closed.
   */
  public final void replaceStream(OutputStream replacementStream) throws IOException {
    if (!isRecoverable()) {
      throw new IllegalStateException();
    }
    if (this.out == replacementStream) {
      return; // Don't replace a stream with itself.
    }
    replayBuffer.writeTo(replacementStream);
    Util.closeQuietly(out);
    out = replacementStream;
  }

  /**
   * Returns a replacement output stream to recover from {@code e} thrown by the
   * previous stream. Returns a new OutputStream if recovery was successful, in
   * which case all previously-written data will be replayed. Returns null if
   * the failure cannot be recovered.
   */
  protected abstract OutputStream replacementStream(IOException e) throws IOException;
}
