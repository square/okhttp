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

import android.util.Log;
import com.google.net.cronet.okhttptransport.RequestResponseConverter.CronetRequestAndOkHttpResponse;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okio.AsyncTimeout;
import okio.Timeout;
import android.net.http.HttpEngine;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.*;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/** A {@link Call.Factory} implementation using Cronet as the transport layer. */
public final class CronetCallFactory implements Call.Factory {

  private static final String TAG = "CronetCallFactory";

  private final RequestResponseConverter converter;
  private final ExecutorService responseCallbackExecutor;
  private final int readTimeoutMillis;
  private final int writeTimeoutMillis;
  private final int callTimeoutMillis;

  private CronetCallFactory(
      RequestResponseConverter converter,
      ExecutorService responseCallbackExecutor,
      int readTimeoutMillis,
      int writeTimeoutMillis,
      int callTimeoutMillis) {
    checkArgument(readTimeoutMillis >= 0, "Read timeout mustn't be negative!");
    checkArgument(writeTimeoutMillis >= 0, "Write timeout mustn't be negative!");
    checkArgument(callTimeoutMillis >= 0, "Call timeout mustn't be negative!");

    this.converter = converter;
    this.responseCallbackExecutor = responseCallbackExecutor;
    this.readTimeoutMillis = readTimeoutMillis;
    this.writeTimeoutMillis = writeTimeoutMillis;
    this.callTimeoutMillis = callTimeoutMillis;
  }

  public static Builder newBuilder(HttpEngine HttpEngine) {
    return new Builder(HttpEngine);
  }

  @Override
  public Call newCall(Request request) {
    return new CronetCall(request, this, converter, responseCallbackExecutor);
  }

  private static class CronetCall implements Call {

    private final Request okHttpRequest;
    private final CronetCallFactory motherFactory;
    private final RequestResponseConverter converter;
    private final ExecutorService responseCallbackExecutor;

    private final AtomicBoolean executed = new AtomicBoolean();
    private final AtomicBoolean canceled = new AtomicBoolean();
    private final AtomicReference<CronetRequestAndOkHttpResponse> convertedRequestAndResponse =
        new AtomicReference<>();
    private final AsyncTimeout timeout;

    private CronetCall(
        Request okHttpRequest,
        CronetCallFactory motherFactory,
        RequestResponseConverter converter,
        ExecutorService responseCallbackExecutor) {
      this.okHttpRequest = okHttpRequest;
      this.motherFactory = motherFactory;
      this.converter = converter;
      this.responseCallbackExecutor = responseCallbackExecutor;

      this.timeout =
          new AsyncTimeout() {
            @Override
            protected void timedOut() {
              CronetCall.this.cancel(); // Timeout has its own method named cancel
            }
          };
      timeout.timeout(motherFactory.callTimeoutMillis, MILLISECONDS);
    }

    @Override
    public Request request() {
      return okHttpRequest;
    }

    @Override
    public Response execute() throws IOException {
      evaluateExecutionPreconditions();
      try {
        timeout.enter();
        CronetRequestAndOkHttpResponse requestAndOkHttpResponse =
            converter.convert(
                request(), motherFactory.readTimeoutMillis, motherFactory.writeTimeoutMillis);
        convertedRequestAndResponse.set(requestAndOkHttpResponse);

        startRequestIfNotCanceled();

        return toCronetCallFactoryResponse(this, requestAndOkHttpResponse.getResponse());
      } catch (RuntimeException | IOException e) {
        // If the request finished successfully don't exit the timeout yet. Reading the body also
        // needs to be considered and the body object will take care of exiting it. See
        // toCronetCallFactoryResponse() for details.
        timeout.exit();
        throw e;
      }
    }

    @Override
    public void enqueue(Callback responseCallback) {
      try {
        timeout.enter();
        evaluateExecutionPreconditions();
        CronetRequestAndOkHttpResponse requestAndOkHttpResponse =
            converter.convert(
                request(), motherFactory.readTimeoutMillis, motherFactory.writeTimeoutMillis);
        convertedRequestAndResponse.set(requestAndOkHttpResponse);
        CronetCall call = this;

        Futures.addCallback(
            requestAndOkHttpResponse.getResponseAsync(),
            new FutureCallback<Response>() {
              @Override
              public void onSuccess(Response result) {
                try {
                  responseCallback.onResponse(call, toCronetCallFactoryResponse(call, result));
                } catch (IOException e) {
                  // The call factory doesn't really mind this - the application code
                  // threw an exception while handling the response, they should have taken care
                  // of it. Just logging the error is consistent with plain OkHttp implementation.
                  Log.i(TAG, "Callback failure for " + toLoggableString(), e);
                }
              }

              @Override
              public void onFailure(Throwable t) {
                if (t instanceof IOException) {
                  responseCallback.onFailure(call, (IOException) t);
                } else {
                  responseCallback.onFailure(call, new IOException(t));
                }
              }
            },
            responseCallbackExecutor);

        startRequestIfNotCanceled();
      } catch (IOException e) {
        // If the request finished successfully don't exit the timeout yet. Reading the body also
        // needs to be considered and the body object will take care of exiting it. See
        // toCronetCallFactoryResponse() for details.
        timeout.exit();
        responseCallback.onFailure(this, e);
      }
    }

    @Override
    public Call clone() {
      return motherFactory.newCall(request());
    }

    @Override
    public void cancel() {
      if (canceled.getAndSet(true)) {
        // already canceled
        return;
      }
      CronetRequestAndOkHttpResponse localConverted = convertedRequestAndResponse.get();
      if (localConverted != null) {
        localConverted.getRequest().cancel();
      } // else the cancel signal will be picked up by the execute() / enqueue() methods.
    }

    @Override
    public boolean isExecuted() {
      return executed.get();
    }

    @Override
    public boolean isCanceled() {
      return canceled.get();
    }

    @Override
    public Timeout timeout() {
      return timeout;
    }

    private String toLoggableString() {
      return "call to " + request().url().redact();
    }

    /**
     * Verifies that the call can be executed and sets the state of the call to "being executed".
     *
     * @throws IllegalStateException if the request has already been executed.
     * @throws IOException if the request was canceled
     */
    private void evaluateExecutionPreconditions() throws IOException {
      if (canceled.get()) {
        throw new IOException("Can't execute canceled requests");
      }
      checkState(!executed.getAndSet(true), "Already Executed");
    }

    private void startRequestIfNotCanceled() {
      CronetRequestAndOkHttpResponse requestAndOkHttpResponse = convertedRequestAndResponse.get();
      checkState(requestAndOkHttpResponse != null, "convertedRequestAndResponse must be set!");

      // There might be a race between the execution and cancellation
      // evaluateExecutionPreconditions check didn't capture and cancel() might have missed that
      // as well. Check once again that the request isn't canceled.
      // This way, no matter how the instructions of the two threads are interleaved, we always end
      // up with a serialized-like outcome (either cancel() was fully run before execute(), or vice
      // versa).

      // Thread 1 (cancel() call)           | Thread 2 (execute() call)
      // -------------------------------------------------------------------------------
      // canceled = true                    | if (canceled) throw;
      // convertedRequest?.cancel()         | convertedRequest = convert(request)
      //                                    | if (canceled) convertedRequest.cancel()
      if (canceled.get()) {
        requestAndOkHttpResponse.getRequest().cancel();
      } else {
        requestAndOkHttpResponse.getRequest().start();
      }
    }
  }

  private static Response toCronetCallFactoryResponse(CronetCall call, Response response) {
    checkNotNull(response.body());

    return response
        .newBuilder()
        .body(
            new CronetTransportResponseBody(response.body()) {
              @Override
              void customCloseHook() {
                call.timeout.exit();
              }
            })
        .build();
  }

  public static final class Builder
      extends RequestResponseConverterBasedBuilder<Builder, CronetCallFactory> {
    private static final int DEFAULT_READ_WRITE_TIMEOUT_MILLIS = 10000;

    private int readTimeoutMillis = DEFAULT_READ_WRITE_TIMEOUT_MILLIS;
    private int writeTimeoutMillis = DEFAULT_READ_WRITE_TIMEOUT_MILLIS;
    private int callTimeoutMillis = 0; // No timeout
    private ExecutorService callbackExecutorService = null;

    Builder(HttpEngine HttpEngine) {
      super(HttpEngine, Builder.class);
    }

    public Builder setReadTimeoutMillis(int readTimeoutMillis) {
      checkArgument(readTimeoutMillis >= 0, "Read timeout mustn't be negative!");
      this.readTimeoutMillis = readTimeoutMillis;
      return this;
    }

    public Builder setWriteTimeoutMillis(int writeTimeoutMillis) {
      checkArgument(writeTimeoutMillis >= 0, "Write timeout mustn't be negative!");
      this.writeTimeoutMillis = writeTimeoutMillis;
      return this;
    }

    public Builder setCallbackExecutorService(ExecutorService callbackExecutorService) {
      checkNotNull(callbackExecutorService);
      this.callbackExecutorService = callbackExecutorService;
      return this;
    }

    public Builder setCallTimeoutMillis(int callTimeoutMillis) {
      checkArgument(callTimeoutMillis >= 0, "Call timeout mustn't be negative!");
      this.callTimeoutMillis = callTimeoutMillis;

      return this;
    }

    @Override
    CronetCallFactory build(RequestResponseConverter converter) {
      ExecutorService localCallbackExecutorService;
      if (callbackExecutorService == null) {
        // Consistent with OkHttp impl
        localCallbackExecutorService = Executors.newCachedThreadPool();
      } else {
        localCallbackExecutorService = callbackExecutorService;
      }

      return new CronetCallFactory(
          converter,
          localCallbackExecutorService,
          readTimeoutMillis,
          writeTimeoutMillis,
          callTimeoutMillis);
    }
  }
}
