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
package okhttp3.internal.huc;

import java.io.IOException;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * This request body involves two threads: an application thread that's writing bytes to {@linkplain
 * HttpURLConnection#getOutputStream() output stream}, and a {@link Dispatcher dispatcher} thread
 * that is {@link RequestBody#writeTo writing bytes to the network}.
 *
 * <p>These two threads operate concurrently, with the application thread producing bytes and the
 * network thread consuming them. Eventually the application thread will complete writing the
 * request, which will trigger the dispatcher to proceed to reading the HTTP response.
 */
final class StreamingRequestBody extends OutputStreamRequestBody {
  private boolean writtenToSink;
  private IOException callbackException;
  private Response callbackResponse;

  public StreamingRequestBody(long expectedContentLength) {
    super(expectedContentLength);
  }

  /**
   * This method is executed by the dispatcher thread. It consumes data from the buffer and writes
   * it to the network.
   */
  @Override public synchronized void writeTo(BufferedSink sink) throws IOException {
    if (writtenToSink) {
      int responseCode = -1; // TODO: what code should this be?
      throw new HttpRetryException("Cannot retry streamed HTTP body", responseCode);
    }
    writtenToSink = true;

    // Continuously consume data from buffer to sink until it is exhausted.
    while (!closed) {
      sink.write(buffer, buffer.size());
      try {
        wait();
      } catch (InterruptedException ignored) {
      }
    }

    // If data was written before the stream was closed, consume it!
    sink.write(buffer, buffer.size());
  }

  /**
   * A callback that is executed on the dispatcher thread when the response is ready. When called
   * this will releases the application thread that is waiting on {@link #awaitResponse()}.
   */
  public Callback callback() {
    return new Callback() {
      @Override public void onFailure(Call call, IOException e) {
        synchronized (StreamingRequestBody.this) {
          callbackException = e;
          StreamingRequestBody.this.notifyAll();
        }
      }

      @Override public void onResponse(Call call, Response response) throws IOException {
        synchronized (StreamingRequestBody.this) {
          callbackResponse = response;
          StreamingRequestBody.this.notifyAll();
        }
      }
    };
  }

  /**
   * This method should be invoked by the application thread. It returns when the dispatcher thread
   * has completed reading the response headers. If the call fails, this will throw an an exception.
   */
  public synchronized Response awaitResponse() throws IOException {
    // The application thread has sent the full request. Close the request body so the dispatcher
    // can proceed to read the response.
    close();

    while (true) {
      if (callbackResponse != null) {
        return callbackResponse;
      } else if (callbackException != null) {
        throw callbackException;
      } else {
        try {
          wait();
        } catch (InterruptedException ignored) {
        }
      }
    }
  }
}
