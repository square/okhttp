/*
 * Copyright 2022 Google LLC
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

package okhttp3.android.httpengine;

import android.util.Pair;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import okio.Buffer;
import okio.Sink;
import okio.Timeout;
import android.net.http.UploadDataSink;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkState;

final class UploadBodyDataBroker implements Sink {

  /**
   * The read request calls to {@link android.net.http.UploadDataProvider#read(UploadDataSink,
   * ByteBuffer)} associated with this broker that we haven't started handling.
   *
   * <p>We don't expect more than one parallel read call for a single request body provider.
   */
  private final BlockingQueue<Pair<ByteBuffer, SettableFuture<ReadResult>>> pendingRead =
      new ArrayBlockingQueue<>(1);

  /**
   * Whether the sink has been closed.
   *
   * <p>Calling close() has no practical use but we check that nobody tries to write to the sink
   * after closing it, which is an indication of misuse.
   */
  private final AtomicBoolean isClosed = new AtomicBoolean();

  /**
   * The exception thrown by the body reading background thread, if any. The exception will be
   * rethrown every time someone attempts to continue reading the body.
   */
  private final AtomicReference<Throwable> backgroundReadThrowable = new AtomicReference<>();

  /**
   * Indicates that Cronet is ready to receive another body part.
   *
   * <p>This method is executed by Cronet's upload data provider.
   */
  Future<ReadResult> enqueueBodyRead(ByteBuffer readBuffer) {
    Throwable backgroundThrowable = backgroundReadThrowable.get();
    if (backgroundThrowable != null) {
      return Futures.immediateFailedFuture(backgroundThrowable);
    }
    SettableFuture<ReadResult> future = SettableFuture.create();
    pendingRead.add(Pair.create(readBuffer, future));

    // Properly handle interleaving handleBackgroundReadError / enqueueBodyRead calls.
    if ((backgroundThrowable = backgroundReadThrowable.get()) != null) {
      future.setException(backgroundThrowable);
    }
    return future;
  }

  /**
   * Signals that reading the OkHttp body failed with the given throwable.
   *
   * <p>This method is executed by the background OkHttp body reading thread.
   */
  void setBackgroundReadError(Throwable t) {
    backgroundReadThrowable.set(t);
    Pair<ByteBuffer, SettableFuture<ReadResult>> read = pendingRead.poll();
    if (read != null) {
      read.second.setException(t);
    }
  }

  /**
   * Signals that reading the body has ended and no future bytes will be sent.
   *
   * <p>This method is executed by the background OkHttp body reading thread.
   */
  void handleEndOfStreamSignal() throws IOException {
    if (isClosed.getAndSet(true)) {
      throw new IllegalStateException("Already closed");
    }

    getPendingCronetRead().second.set(ReadResult.END_OF_BODY);
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method is executed by the background OkHttp body reading thread.
   */
  @Override
  public void write(Buffer source, long byteCount) throws IOException {
    // This is just a safeguard, close() is a no-op if the body length contract is honored.
    checkState(!isClosed.get());

    long bytesRemaining = byteCount;

    while (bytesRemaining != 0) {
      Pair<ByteBuffer, SettableFuture<ReadResult>> payload = getPendingCronetRead();

      ByteBuffer readBuffer = payload.first;
      SettableFuture<ReadResult> future = payload.second;

      int originalBufferLimit = readBuffer.limit();
      int bytesToDrain = (int) Math.min(originalBufferLimit, bytesRemaining);

      readBuffer.limit(bytesToDrain);

      try {
        long bytesRead = source.read(readBuffer);
        if (bytesRead == -1) {
          IOException e = new IOException("The source has been exhausted but we expected more!");
          future.setException(e);
          throw e;
        }
        bytesRemaining -= bytesRead;
        readBuffer.limit(originalBufferLimit);
        future.set(ReadResult.SUCCESS);
      } catch (IOException e) {
        future.setException(e);
        throw e;
      }
    }
  }

  private Pair<ByteBuffer, SettableFuture<ReadResult>> getPendingCronetRead() throws IOException {
    try {
      return pendingRead.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for a read to finish!");
    }
  }

  @Override
  public void close() {
    isClosed.set(true);
  }

  @Override
  public void flush() {
    // Not necessary, we "flush" by sending the data to Cronet straight away when write() is called.
    // Note that this class is wrapped with a okio buffer so writes to the outer layer won't be
    // seen by this class immediately.
  }

  @Override
  public Timeout timeout() {
    return Timeout.NONE;
  }

  enum ReadResult {
    SUCCESS,
    END_OF_BODY
  }
}
