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

import androidx.annotation.VisibleForTesting;
import com.google.common.base.Verify;
import com.google.common.util.concurrent.*;
import com.google.net.cronet.okhttptransport.UploadBodyDataBroker.ReadResult;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.Okio;
import android.net.http.UploadDataProvider;
import android.net.http.UploadDataSink;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

final class RequestBodyConverterImpl implements RequestBodyConverter {

  private static final long IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES = 1024 * 1024;

  private final InMemoryRequestBodyConverter inMemoryRequestBodyConverter;
  private final StreamingRequestBodyConverter streamingRequestBodyConverter;

  RequestBodyConverterImpl(
      InMemoryRequestBodyConverter inMemoryConverter,
      StreamingRequestBodyConverter streamingConverter) {
    this.inMemoryRequestBodyConverter = inMemoryConverter;
    this.streamingRequestBodyConverter = streamingConverter;
  }

  static RequestBodyConverterImpl create(ExecutorService bodyReaderExecutor) {
    return new RequestBodyConverterImpl(
        new InMemoryRequestBodyConverter(), new StreamingRequestBodyConverter(bodyReaderExecutor));
  }

  @Override
  public UploadDataProvider convertRequestBody(RequestBody requestBody, int writeTimeoutMillis)
      throws IOException {
    long contentLength = requestBody.contentLength();
    if (contentLength == -1 || contentLength > IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES) {
      return streamingRequestBodyConverter.convertRequestBody(requestBody, writeTimeoutMillis);
    } else {
      return inMemoryRequestBodyConverter.convertRequestBody(requestBody, writeTimeoutMillis);
    }
  }

  /**
   * Implementation of {@link RequestBodyConverter} that doesn't need to hold the entire request
   * body in memory.
   *
   * <p>
   *
   * <ol>
   *   <li>{@link RequestBody#writeTo(BufferedSink)} is invoked on the body, but the sink doesn't
   *       accept any data
   *   <li>A call to {@link UploadDataProvider#read(UploadDataSink, ByteBuffer)} unblocks the sink,
   *       which accepts a part of the body (size depends on the buffer's capacity), then blocks
   *       again. Buffer is sent to Cronet.
   * </ol>
   *
   * This is repeated until the entire body has been read.
   */
  @VisibleForTesting
  static final class StreamingRequestBodyConverter implements RequestBodyConverter {

    private final ExecutorService readerExecutor;

    StreamingRequestBodyConverter(ExecutorService readerExecutor) {
      this.readerExecutor = readerExecutor;
    }

    @Override
    public UploadDataProvider convertRequestBody(RequestBody requestBody, int writeTimeoutMillis) {
      return new StreamingUploadDataProvider(
          requestBody, new UploadBodyDataBroker(), readerExecutor, writeTimeoutMillis);
    }

    private static class StreamingUploadDataProvider extends UploadDataProvider {
      private final RequestBody okHttpRequestBody;
      private final UploadBodyDataBroker broker;
      private final ListeningExecutorService readTaskExecutor;
      private final long writeTimeoutMillis;

      /** The future for the task that reads the OkHttp request body in the background. */
      private ListenableFuture<?> readTaskFuture;

      /** The number of bytes we read from the OkHttp body thus far. */
      private long totalBytesReadFromOkHttp;

      private StreamingUploadDataProvider(
          RequestBody okHttpRequestBody,
          UploadBodyDataBroker broker,
          ExecutorService readTaskExecutor,
          long writeTimeoutMillis) {
        this.okHttpRequestBody = okHttpRequestBody;
        this.broker = broker;
        if (readTaskExecutor instanceof ListeningExecutorService) {
          this.readTaskExecutor = (ListeningExecutorService) readTaskExecutor;
        } else {
          this.readTaskExecutor = MoreExecutors.listeningDecorator(readTaskExecutor);
        }

        // So that we don't have to special case infinity. Int.MAX_VALUE is ~infinity for all
        // practical use cases.
        this.writeTimeoutMillis = writeTimeoutMillis == 0 ? Integer.MAX_VALUE : writeTimeoutMillis;
      }

      @Override
      public long getLength() throws IOException {
        return okHttpRequestBody.contentLength();
      }

      @Override
      public void read(UploadDataSink uploadDataSink, ByteBuffer byteBuffer) throws IOException {
        ensureReadTaskStarted();

        if (getLength() == -1) {
          readUnknownBodyLength(uploadDataSink, byteBuffer);
        } else {
          readKnownBodyLength(uploadDataSink, byteBuffer);
        }
      }

      private void readKnownBodyLength(UploadDataSink uploadDataSink, ByteBuffer byteBuffer)
          throws IOException {
        try {
          UploadBodyDataBroker.ReadResult readResult = readFromOkHttp(byteBuffer);

          if (totalBytesReadFromOkHttp > getLength()) {
            throw prepareBodyTooLongException(getLength(), totalBytesReadFromOkHttp);
          }

          if (totalBytesReadFromOkHttp < getLength()) {
            switch (readResult) {
              case SUCCESS:
                uploadDataSink.onReadSucceeded(false);
                break;
              case END_OF_BODY:
                throw new IOException("The source has been exhausted but we expected more data!");
            }
            return;
          }
          // Else we're handling what's supposed to be the last chunk
          handleLastBodyRead(uploadDataSink, byteBuffer);

        } catch (TimeoutException | ExecutionException e) {
          readTaskFuture.cancel(true);
          uploadDataSink.onReadError(new IOException(e));
        }
      }

      /**
       * The last body read is special for fixed length bodies - if Cronet receives exactly the
       * right amount of data it won't ask for more, even if there is more data in the stream. As a
       * result, when we read the advertised number of bytes, we need to make sure that the stream
       * is indeed finished.
       */
      private void handleLastBodyRead(UploadDataSink uploadDataSink, ByteBuffer filledByteBuffer)
          throws IOException, TimeoutException, ExecutionException {
        // We reuse the same buffer for the END_OF_DATA read (it should be non-destructive and if
        // it overwrites what's in there we don't mind as that's an error anyway). We just need
        // to make sure we restore the original position afterwards. We don't use mark() / reset()
        // as the mark position can be invalidated by limit manipulation.
        int bufferPosition = filledByteBuffer.position();
        filledByteBuffer.position(0);

        UploadBodyDataBroker.ReadResult readResult = readFromOkHttp(filledByteBuffer);

        if (!readResult.equals(ReadResult.END_OF_BODY)) {
          throw prepareBodyTooLongException(getLength(), totalBytesReadFromOkHttp);
        }

        Verify.verify(
            filledByteBuffer.position() == 0,
            "END_OF_BODY reads shouldn't write anything to the buffer");

        // revert the position change
        filledByteBuffer.position(bufferPosition);

        uploadDataSink.onReadSucceeded(false);
      }

      private void readUnknownBodyLength(UploadDataSink uploadDataSink, ByteBuffer byteBuffer) {
        try {
          UploadBodyDataBroker.ReadResult readResult = readFromOkHttp(byteBuffer);
          uploadDataSink.onReadSucceeded(readResult.equals(ReadResult.END_OF_BODY));
        } catch (TimeoutException | ExecutionException e) {
          readTaskFuture.cancel(true);
          uploadDataSink.onReadError(new IOException(e));
        }
      }

      private void ensureReadTaskStarted() {
        // We don't expect concurrent calls so a simple flag is sufficient
        if (readTaskFuture == null) {
          readTaskFuture =
              readTaskExecutor.submit(
                  (Callable<Void>)
                      () -> {
                        BufferedSink bufferedSink = Okio.buffer(broker);
                        okHttpRequestBody.writeTo(bufferedSink);
                        bufferedSink.flush();
                        broker.handleEndOfStreamSignal();
                        return null;
                      });

          Futures.addCallback(
              readTaskFuture,
              new FutureCallback<Object>() {
                @Override
                public void onSuccess(Object result) {}

                @Override
                public void onFailure(Throwable t) {
                  broker.setBackgroundReadError(t);
                }
              },
              MoreExecutors.directExecutor());
        }
      }

      private ReadResult readFromOkHttp(ByteBuffer byteBuffer)
          throws TimeoutException, ExecutionException {
        int positionBeforeRead = byteBuffer.position();
        UploadBodyDataBroker.ReadResult readResult =
            Uninterruptibles.getUninterruptibly(
                broker.enqueueBodyRead(byteBuffer), writeTimeoutMillis, MILLISECONDS);
        int bytesRead = byteBuffer.position() - positionBeforeRead;
        totalBytesReadFromOkHttp += bytesRead;
        return readResult;
      }

      private static IOException prepareBodyTooLongException(
          long expectedLength, long minActualLength) {
        return new IOException(
            "Expected " + expectedLength + " bytes but got at least " + minActualLength);
      }

      @Override
      public void rewind(UploadDataSink uploadDataSink) {
        // TODO(danstahr): OkHttp 4 can use isOneShot flag here and rewind safely.
        uploadDataSink.onRewindError(new UnsupportedOperationException("Rewind is not supported!"));
      }
    }
  }

  /**
   * Converts OkHttp's {@link RequestBody} to Cronet's {@link UploadDataProvider} by materializing
   * the body in memory first.
   *
   * <p>This strategy shouldn't be used for large requests (and for requests with uncapped length)
   * to avoid OOM issues.
   */
  @VisibleForTesting
  static final class InMemoryRequestBodyConverter implements RequestBodyConverter {

    @Override
    public UploadDataProvider convertRequestBody(RequestBody requestBody, int writeTimeoutMillis)
        throws IOException {

      // content length is immutable by contract
      long length = requestBody.contentLength();
      if (length < 0 || length > IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES) {
        throw new IOException(
            "Expected definite length less than "
                + IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES
                + "but got "
                + length);
      }

      return new UploadDataProvider() {
        private volatile boolean isMaterialized = false;
        private final Buffer materializedBody = new Buffer();

        @Override
        public long getLength() {
          return length;
        }

        @Override
        public void read(UploadDataSink uploadDataSink, ByteBuffer byteBuffer) throws IOException {
          // We're not expecting any concurrent calls here so a simple flag should be sufficient.
          if (!isMaterialized) {
            requestBody.writeTo(materializedBody);
            materializedBody.flush();
            isMaterialized = true;
            long reportedLength = getLength();
            long actualLength = materializedBody.size();
            if (actualLength != reportedLength) {
              throw new IOException(
                  "Expected " + reportedLength + " bytes but got " + actualLength);
            }
          }
          if (materializedBody.read(byteBuffer) == -1) {
            // This should never happen - for known body length we shouldn't be called at all
            // if there's no more data to read.
            throw new IllegalStateException("The source has been exhausted but we expected more!");
          }
          uploadDataSink.onReadSucceeded(false);
        }

        @Override
        public void rewind(UploadDataSink uploadDataSink) {
          // TODO(danstahr): OkHttp 4 can use isOneShot flag here and rewind safely.
          uploadDataSink.onRewindError(new UnsupportedOperationException());
        }
      };
    }
  }
}
