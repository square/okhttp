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
package okhttp3.android.httpengine

import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import android.os.Build
import androidx.annotation.RequiresExtension
import androidx.annotation.VisibleForTesting
import com.google.common.base.Verify
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.Uninterruptibles
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.Volatile
import okhttp3.RequestBody
import okhttp3.android.httpengine.UploadBodyDataBroker.ReadResult
import okio.Buffer
import okio.BufferedSink
import okio.buffer

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
internal class RequestBodyConverterImpl(
  private val inMemoryRequestBodyConverter: InMemoryRequestBodyConverter,
  private val streamingRequestBodyConverter: StreamingRequestBodyConverter,
) : RequestBodyConverter {
  @Throws(IOException::class)
  override fun convertRequestBody(
    requestBody: RequestBody,
    writeTimeoutMillis: Int,
  ): UploadDataProvider {
    val contentLength = requestBody.contentLength()
    if (contentLength == -1L || contentLength > IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES) {
      return streamingRequestBodyConverter.convertRequestBody(requestBody, writeTimeoutMillis)
    } else {
      return inMemoryRequestBodyConverter.convertRequestBody(requestBody, writeTimeoutMillis)
    }
  }

  /**
   * Implementation of [RequestBodyConverter] that doesn't need to hold the entire request
   * body in memory.
   *
   *
   *
   *
   *
   *  1. [RequestBody.writeTo] is invoked on the body, but the sink doesn't
   * accept any data
   *  1. A call to [UploadDataProvider.read] unblocks the sink,
   * which accepts a part of the body (size depends on the buffer's capacity), then blocks
   * again. Buffer is sent to Cronet.
   *
   *
   * This is repeated until the entire body has been read.
   */
  @VisibleForTesting
  internal class StreamingRequestBodyConverter(
    private val readerExecutor: ExecutorService,
  ) : RequestBodyConverter {
    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    override fun convertRequestBody(
      requestBody: RequestBody,
      writeTimeoutMillis: Int,
    ): UploadDataProvider =
      StreamingUploadDataProvider(
        requestBody,
        UploadBodyDataBroker(),
        readerExecutor,
        writeTimeoutMillis.toLong(),
      )

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    private class StreamingUploadDataProvider(
      private val okHttpRequestBody: RequestBody,
      private val broker: UploadBodyDataBroker,
      readTaskExecutor: ExecutorService,
      writeTimeoutMillis: Long,
    ) : UploadDataProvider() {
      private val readTaskExecutor: ListeningExecutorService =
        readTaskExecutor as? ListeningExecutorService ?: MoreExecutors.listeningDecorator(readTaskExecutor)

      // So that we don't have to special case infinity. Int.MAX_VALUE is ~infinity for all
      // practical use cases.
      private val writeTimeoutMillis: Long =
        if (writeTimeoutMillis == 0L) Int.MAX_VALUE.toLong() else writeTimeoutMillis

      /** The future for the task that reads the OkHttp request body in the background.  */
      private var readTaskFuture: ListenableFuture<Unit>? = null

      /** The number of bytes we read from the OkHttp body thus far.  */
      private var totalBytesReadFromOkHttp: Long = 0

      @Throws(IOException::class)
      override fun getLength(): Long = okHttpRequestBody.contentLength()

      @Throws(IOException::class)
      override fun read(
        uploadDataSink: UploadDataSink,
        byteBuffer: ByteBuffer,
      ) {
        ensureReadTaskStarted()

        if (length == -1L) {
          readUnknownBodyLength(uploadDataSink, byteBuffer)
        } else {
          readKnownBodyLength(uploadDataSink, byteBuffer)
        }
      }

      @Throws(IOException::class)
      fun readKnownBodyLength(
        uploadDataSink: UploadDataSink,
        byteBuffer: ByteBuffer,
      ) {
        try {
          val readResult: ReadResult = readFromOkHttp(byteBuffer)

          if (totalBytesReadFromOkHttp > length) {
            throw prepareBodyTooLongException(length, totalBytesReadFromOkHttp)
          }

          if (totalBytesReadFromOkHttp < length) {
            when (readResult) {
              ReadResult.SUCCESS -> uploadDataSink.onReadSucceeded(false)
              ReadResult.END_OF_BODY -> throw IOException("The source has been exhausted but we expected more data!")
            }
            return
          }
          // Else we're handling what's supposed to be the last chunk
          handleLastBodyRead(uploadDataSink, byteBuffer)
        } catch (e: TimeoutException) {
          readTaskFuture!!.cancel(true)
          uploadDataSink.onReadError(IOException(e))
        } catch (e: ExecutionException) {
          readTaskFuture!!.cancel(true)
          uploadDataSink.onReadError(IOException(e))
        }
      }

      /**
       * The last body read is special for fixed length bodies - if Cronet receives exactly the
       * right amount of data it won't ask for more, even if there is more data in the stream. As a
       * result, when we read the advertised number of bytes, we need to make sure that the stream
       * is indeed finished.
       */
      fun handleLastBodyRead(
        uploadDataSink: UploadDataSink,
        filledByteBuffer: ByteBuffer,
      ) {
        // We reuse the same buffer for the END_OF_DATA read (it should be non-destructive and if
        // it overwrites what's in there we don't mind as that's an error anyway). We just need
        // to make sure we restore the original position afterwards. We don't use mark() / reset()
        // as the mark position can be invalidated by limit manipulation.
        val bufferPosition = filledByteBuffer.position()
        filledByteBuffer.position(0)

        val readResult: ReadResult = readFromOkHttp(filledByteBuffer)

        if (readResult != ReadResult.END_OF_BODY) {
          throw prepareBodyTooLongException(length, totalBytesReadFromOkHttp)
        }

        Verify.verify(
          filledByteBuffer.position() == 0,
          "END_OF_BODY reads shouldn't write anything to the buffer",
        )

        // revert the position change
        filledByteBuffer.position(bufferPosition)

        uploadDataSink.onReadSucceeded(false)
      }

      fun readUnknownBodyLength(
        uploadDataSink: UploadDataSink,
        byteBuffer: ByteBuffer,
      ) {
        try {
          val readResult: ReadResult = readFromOkHttp(byteBuffer)
          uploadDataSink.onReadSucceeded(readResult == ReadResult.END_OF_BODY)
        } catch (e: TimeoutException) {
          readTaskFuture!!.cancel(true)
          uploadDataSink.onReadError(IOException(e))
        } catch (e: ExecutionException) {
          readTaskFuture!!.cancel(true)
          uploadDataSink.onReadError(IOException(e))
        }
      }

      fun ensureReadTaskStarted() {
        // We don't expect concurrent calls so a simple flag is sufficient
        if (readTaskFuture == null) {
          readTaskFuture =
            readTaskExecutor.submit(
              Callable {
                val bufferedSink: BufferedSink = broker.buffer()
                okHttpRequestBody.writeTo(bufferedSink)
                bufferedSink.flush()
                broker.handleEndOfStreamSignal()
              },
            )

          Futures.addCallback(
            readTaskFuture!!,
            object : FutureCallback<Unit> {
              override fun onSuccess(result: Unit) {}

              override fun onFailure(t: Throwable) {
                broker.setBackgroundReadError(t)
              }
            },
            MoreExecutors.directExecutor(),
          )
        }
      }

      @Throws(TimeoutException::class, ExecutionException::class)
      fun readFromOkHttp(byteBuffer: ByteBuffer): ReadResult {
        val positionBeforeRead = byteBuffer.position()
        val readResult: ReadResult =
          Uninterruptibles.getUninterruptibly(
            broker.enqueueBodyRead(byteBuffer),
            writeTimeoutMillis,
            TimeUnit.MILLISECONDS,
          )
        val bytesRead = byteBuffer.position() - positionBeforeRead
        totalBytesReadFromOkHttp += bytesRead.toLong()
        return readResult
      }

      override fun rewind(uploadDataSink: UploadDataSink) {
        // TODO(danstahr): OkHttp 4 can use isOneShot flag here and rewind safely.
        uploadDataSink.onRewindError(UnsupportedOperationException("Rewind is not supported!"))
      }

      companion object {
        private fun prepareBodyTooLongException(
          expectedLength: Long,
          minActualLength: Long,
        ): IOException =
          IOException(
            "Expected " + expectedLength + " bytes but got at least " + minActualLength,
          )
      }
    }
  }

  /**
   * Converts OkHttp's [RequestBody] to Cronet's [UploadDataProvider] by materializing
   * the body in memory first.
   *
   *
   * This strategy shouldn't be used for large requests (and for requests with uncapped length)
   * to avoid OOM issues.
   */
  @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
  @VisibleForTesting
  internal class InMemoryRequestBodyConverter : RequestBodyConverter {
    @Throws(IOException::class)
    override fun convertRequestBody(
      requestBody: RequestBody,
      writeTimeoutMillis: Int,
    ): UploadDataProvider {
      // content length is immutable by contract

      val length = requestBody.contentLength()
      if (length < 0 || length > IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES) {
        throw IOException(
          (
            "Expected definite length less than " +
              IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES +
              "but got " +
              length
          ),
        )
      }

      return object : UploadDataProvider() {
        @Volatile
        private var isMaterialized = false
        private val materializedBody = Buffer()

        override fun getLength(): Long = length

        @Throws(IOException::class)
        override fun read(
          uploadDataSink: UploadDataSink,
          byteBuffer: ByteBuffer,
        ) {
          // We're not expecting any concurrent calls here so a simple flag should be sufficient.
          if (!isMaterialized) {
            requestBody.writeTo(materializedBody)
            materializedBody.flush()
            isMaterialized = true
            val reportedLength = getLength()
            val actualLength = materializedBody.size
            if (actualLength != reportedLength) {
              throw IOException(
                "Expected " + reportedLength + " bytes but got " + actualLength,
              )
            }
          }
          check(materializedBody.read(byteBuffer) != -1) { "The source has been exhausted but we expected more!" }
          uploadDataSink.onReadSucceeded(false)
        }

        override fun rewind(uploadDataSink: UploadDataSink) {
          // TODO(danstahr): OkHttp 4 can use isOneShot flag here and rewind safely.
          uploadDataSink.onRewindError(UnsupportedOperationException())
        }
      }
    }
  }

  companion object {
    private val IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES = (1024 * 1024).toLong()

    fun create(bodyReaderExecutor: ExecutorService): RequestBodyConverterImpl =
      RequestBodyConverterImpl(
        InMemoryRequestBodyConverter(),
        StreamingRequestBodyConverter(bodyReaderExecutor),
      )
  }
}
