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
package com.google.net.cronet.okhttptransportU

import android.util.Pair
import androidx.annotation.RequiresApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okio.Buffer
import okio.Sink
import okio.Timeout

@RequiresApi(34)
internal class UploadBodyDataBroker : Sink {
  /**
   * The read request calls to [android.net.http.UploadDataProvider.read] associated with this broker that we haven't started handling.
   *
   *
   * We don't expect more than one parallel read call for a single request body provider.
   */
  private val pendingRead: BlockingQueue<Pair<ByteBuffer, SettableFuture<ReadResult>>> = ArrayBlockingQueue(1)

  /**
   * Whether the sink has been closed.
   *
   *
   * Calling close() has no practical use but we check that nobody tries to write to the sink
   * after closing it, which is an indication of misuse.
   */
  private val isClosed = AtomicBoolean()

  /**
   * The exception thrown by the body reading background thread, if any. The exception will be
   * rethrown every time someone attempts to continue reading the body.
   */
  private val backgroundReadThrowable = AtomicReference<Throwable>()

  /**
   * Indicates that Cronet is ready to receive another body part.
   *
   *
   * This method is executed by Cronet's upload data provider.
   */
  fun enqueueBodyRead(readBuffer: ByteBuffer): Future<ReadResult> {
    var backgroundThrowable = backgroundReadThrowable.get()
    if (backgroundThrowable != null) {
      return Futures.immediateFailedFuture(backgroundThrowable)
    }
    val future = SettableFuture.create<ReadResult>()
    pendingRead.add(Pair.create(readBuffer, future))

    // Properly handle interleaving handleBackgroundReadError / enqueueBodyRead calls.
    if (backgroundReadThrowable.get().also { backgroundThrowable = it } != null) {
      future.setException(backgroundThrowable!!)
    }
    return future
  }

  /**
   * Signals that reading the OkHttp body failed with the given throwable.
   *
   *
   * This method is executed by the background OkHttp body reading thread.
   */
  fun setBackgroundReadError(t: Throwable) {
    backgroundReadThrowable.set(t)
    val read = pendingRead.poll()
    read?.second?.setException(t)
  }

  /**
   * Signals that reading the body has ended and no future bytes will be sent.
   *
   *
   * This method is executed by the background OkHttp body reading thread.
   */
  fun handleEndOfStreamSignal() {
    check(!isClosed.getAndSet(true)) { "Already closed" }
    pendingCronetRead.second.set(ReadResult.END_OF_BODY)
  }

  /**
   * {@inheritDoc}
   *
   *
   * This method is executed by the background OkHttp body reading thread.
   */
  override fun write(source: Buffer, byteCount: Long) {
    // This is just a safeguard, close() is a no-op if the body length contract is honored.
    check(!isClosed.get())
    var bytesRemaining = byteCount
    while (bytesRemaining != 0L) {
      val payload = pendingCronetRead
      val readBuffer = payload.first
      val future = payload.second
      val originalBufferLimit = readBuffer.limit()
      val bytesToDrain = Math.min(originalBufferLimit.toLong(), bytesRemaining).toInt()
      readBuffer.limit(bytesToDrain)
      try {
        val bytesRead = source.read(readBuffer).toLong()
        if (bytesRead == -1L) {
          val e = IOException("The source has been exhausted but we expected more!")
          future.setException(e)
          throw e
        }
        bytesRemaining -= bytesRead
        readBuffer.limit(originalBufferLimit)
        future.set(ReadResult.SUCCESS)
      } catch (e: IOException) {
        future.setException(e)
        throw e
      }
    }
  }

  private val pendingCronetRead: Pair<ByteBuffer, SettableFuture<ReadResult>>
    private get() = try {
      pendingRead.take()
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw IOException("Interrupted while waiting for a read to finish!")
    }

  override fun close() {
    isClosed.set(true)
  }

  override fun flush() {
    // Not necessary, we "flush" by sending the data to Cronet straight away when write() is called.
    // Note that this class is wrapped with a okio buffer so writes to the outer layer won't be
    // seen by this class immediately.
  }

  override fun timeout(): Timeout {
    return Timeout.NONE
  }

  internal enum class ReadResult {
    SUCCESS,
    END_OF_BODY
  }
}
