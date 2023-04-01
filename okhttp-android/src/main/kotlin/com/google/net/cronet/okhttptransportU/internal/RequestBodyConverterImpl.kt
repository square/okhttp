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
package com.google.net.cronet.okhttptransportU.internal

import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import com.google.net.cronet.okhttptransportU.internal.OkHttpBridgeRequestCallback.Companion.timeoutOrMax
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.RequestBody
import okio.Buffer
import okio.buffer

@RequiresApi(34)
internal class RequestBodyConverterImpl(
  private val inMemoryRequestBodyConverter: InMemoryRequestBodyConverter,
  private val streamingRequestBodyConverter: StreamingRequestBodyConverter) : RequestBodyConverter {

  override fun convertRequestBody(requestBody: RequestBody, writeTimeoutMillis: Int): UploadDataProvider {
    val contentLength = requestBody.contentLength()
    return if (contentLength == -1L || contentLength > IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES) {
      streamingRequestBodyConverter.convertRequestBody(requestBody, writeTimeoutMillis)
    } else {
      inMemoryRequestBodyConverter.convertRequestBody(requestBody, writeTimeoutMillis)
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
  internal class StreamingRequestBodyConverter(private val readerExecutor: CoroutineScope) : RequestBodyConverter {
    override fun convertRequestBody(requestBody: RequestBody, writeTimeoutMillis: Int): UploadDataProvider {
      return StreamingUploadDataProvider(
        requestBody, UploadBodyDataBroker(), readerExecutor, writeTimeoutMillis.toLong())
    }

    private class StreamingUploadDataProvider(
      private val okHttpRequestBody: RequestBody,
      private val broker: UploadBodyDataBroker,
      private val readTaskExecutor: CoroutineScope,
      private val writeTimeoutMillis: Long) : UploadDataProvider() {

      /** The future for the task that reads the OkHttp request body in the background.  */
      private var readTaskFuture: Job? = null

      /** The number of bytes we read from the OkHttp body thus far.  */
      private var totalBytesReadFromOkHttp: Long = 0

      @Throws(IOException::class)
      override fun getLength(): Long {
        return okHttpRequestBody.contentLength()
      }

      override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        ensureReadTaskStarted()
        if (length == -1L) {
          readUnknownBodyLength(uploadDataSink, byteBuffer)
        } else {
          readKnownBodyLength(uploadDataSink, byteBuffer)
        }
      }

      private fun readKnownBodyLength(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        try {
          val readResult = readFromOkHttp(byteBuffer)
          if (totalBytesReadFromOkHttp > length) {
            throw prepareBodyTooLongException(length, totalBytesReadFromOkHttp)
          }
          if (totalBytesReadFromOkHttp < length) {
            when (readResult) {
              UploadBodyDataBroker.ReadResult.SUCCESS -> uploadDataSink.onReadSucceeded(false)
              UploadBodyDataBroker.ReadResult.END_OF_BODY -> throw IOException("The source has been exhausted but we expected more data!")
            }
            return
          }
          // Else we're handling what's supposed to be the last chunk
          handleLastBodyRead(uploadDataSink, byteBuffer)
        } catch (e: TimeoutException) {
          readTaskFuture!!.cancel()
          uploadDataSink.onReadError(IOException(e))
        } catch (e: ExecutionException) {
          readTaskFuture!!.cancel()
          uploadDataSink.onReadError(IOException(e))
        }
      }

      /**
       * The last body read is special for fixed length bodies - if Cronet receives exactly the
       * right amount of data it won't ask for more, even if there is more data in the stream. As a
       * result, when we read the advertised number of bytes, we need to make sure that the stream
       * is indeed finished.
       */
      @Throws(IOException::class, TimeoutException::class, ExecutionException::class)
      private fun handleLastBodyRead(uploadDataSink: UploadDataSink, filledByteBuffer: ByteBuffer) {
        // We reuse the same buffer for the END_OF_DATA read (it should be non-destructive and if
        // it overwrites what's in there we don't mind as that's an error anyway). We just need
        // to make sure we restore the original position afterwards. We don't use mark() / reset()
        // as the mark position can be invalidated by limit manipulation.
        val bufferPosition = filledByteBuffer.position()
        filledByteBuffer.position(0)
        val readResult = readFromOkHttp(filledByteBuffer)
        if (readResult != UploadBodyDataBroker.ReadResult.END_OF_BODY) {
          throw prepareBodyTooLongException(length, totalBytesReadFromOkHttp)
        }
        check(
          filledByteBuffer.position() == 0
        ) { "END_OF_BODY reads shouldn't write anything to the buffer" }

        // revert the position change
        filledByteBuffer.position(bufferPosition)
        uploadDataSink.onReadSucceeded(false)
      }

      private fun readUnknownBodyLength(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        try {
          val readResult = readFromOkHttp(byteBuffer)
          uploadDataSink.onReadSucceeded(readResult == UploadBodyDataBroker.ReadResult.END_OF_BODY)
        } catch (e: TimeoutException) {
          readTaskFuture!!.cancel()
          uploadDataSink.onReadError(IOException(e))
        } catch (e: ExecutionException) {
          readTaskFuture!!.cancel()
          uploadDataSink.onReadError(IOException(e))
        }
      }

      private fun ensureReadTaskStarted() {
        // We don't expect concurrent calls so a simple flag is sufficient
        check(readTaskFuture == null)
        readTaskFuture = readTaskExecutor.launch {
          try {
            val bufferedSink = broker.buffer()
            okHttpRequestBody.writeTo(bufferedSink)
            bufferedSink.flush()
            broker.handleEndOfStreamSignal()
          } catch (e: Exception) {
            broker.setBackgroundReadError(e)
          }
        }
      }

      private fun readFromOkHttp(byteBuffer: ByteBuffer): UploadBodyDataBroker.ReadResult {
        val positionBeforeRead = byteBuffer.position()
        val readResult = runBlocking {
          withTimeout(writeTimeoutMillis.timeoutOrMax) {
            broker.enqueueBodyRead(byteBuffer).await()
          }
        }
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
          expectedLength: Long, minActualLength: Long): IOException {
          return IOException(
            "Expected $expectedLength bytes but got at least $minActualLength")
        }
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
  @VisibleForTesting
  internal class InMemoryRequestBodyConverter : RequestBodyConverter {
    override fun convertRequestBody(requestBody: RequestBody, writeTimeoutMillis: Int): UploadDataProvider {

      // content length is immutable by contract
      val length = requestBody.contentLength()
      if (length < 0 || length > IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES) {
        throw IOException(
          "Expected definite length less than "
            + IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES
            + "but got "
            + length)
      }
      return object : UploadDataProvider() {
        @Volatile
        private var isMaterialized = false
        private val materializedBody = Buffer()
        override fun getLength(): Long {
          return length
        }

        override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
          // We're not expecting any concurrent calls here so a simple flag should be sufficient.
          if (!isMaterialized) {
            requestBody.writeTo(materializedBody)
            materializedBody.flush()
            isMaterialized = true
            val reportedLength = getLength()
            val actualLength = materializedBody.size
            if (actualLength != reportedLength) {
              throw IOException(
                "Expected $reportedLength bytes but got $actualLength")
            }
          }
          check(materializedBody.read(byteBuffer) != -1) {
            // This should never happen - for known body length we shouldn't be called at all
            // if there's no more data to read.
            "The source has been exhausted but we expected more!"
          }
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
    private const val IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES = (1024 * 1024).toLong()
    fun create(bodyReaderExecutor: CoroutineScope): RequestBodyConverterImpl {
      return RequestBodyConverterImpl(
        InMemoryRequestBodyConverter(), StreamingRequestBodyConverter(bodyReaderExecutor))
    }
  }
}
