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

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import okio.Buffer;
import okio.Source;
import okio.Timeout;
import android.net.http.CronetException;
import android.net.http.UrlRequest;
import android.net.http.UrlResponseInfo;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * An implementation of Cronet's callback. This is the heart of the bridge and deals with most of
 * the async-sync paradigm translation.
 *
 * <p>Translating the UrlResponseInfo is relatively straightforward as the entire object is
 * available immediately and is relatively small, so it can easily fit in memory.
 *
 * <p>Translating the body is a bit more tricky because of the mismatch between OkHttp and Cronet
 * designs. We invoke Cronet's read and wait for the result using synchronization primitives (see
 * BodySource implementation). The implementation is assuming that there's always at most one read()
 * request in flight (which is safe to assume), and relies on reasonable fairness of thread
 * scheduling, especially when handling cancellations.
 */
class OkHttpBridgeRequestCallback extends UrlRequest.Callback {

  /**
   * The byte buffer capacity for reading Cronet response bodies. Each response callback will
   * allocate its own buffer of this size once the response starts being processed.
   */
  private static final int CRONET_BYTE_BUFFER_CAPACITY = 32 * 1024;

  /** A bridge between Cronet's asynchronous callbacks and OkHttp's blocking stream-like reads. */
  private final SettableFuture<Source> bodySourceFuture = SettableFuture.create();

  /** Signal whether the request is finished and the response has been fully read. */
  private final AtomicBoolean finished = new AtomicBoolean(false);

  /** Signal whether the request was canceled. */
  private final AtomicBoolean canceled = new AtomicBoolean(false);

  /**
   * An internal, blocking, thread safe way of passing data between the callback methods and {@link
   * #bodySourceFuture}.
   *
   * <p>Has a capacity of 2 - at most one slot for a read result and at most 1 slot for cancellation
   * signal, this guarantees that all inserts are non blocking.
   */
  private final BlockingQueue<CallbackResult> callbackResults = new ArrayBlockingQueue<>(2);

  /** The response headers. */
  private final SettableFuture<UrlResponseInfo> headersFuture = SettableFuture.create();

  /** The read timeout as specified by OkHttp. * */
  private final long readTimeoutMillis;

  /** The previous responses as reported to {@link #onRedirectReceived}, from oldest to newest. * */
  private final List<UrlResponseInfo> urlResponseInfoChain = new ArrayList<>();

  private final RedirectStrategy redirectStrategy;

  /** The request being processed. Set when the request is first seen by the callback. */
  private volatile UrlRequest request;

  OkHttpBridgeRequestCallback(long readTimeoutMillis, RedirectStrategy redirectStrategy) {
    checkArgument(readTimeoutMillis >= 0);

    // So that we don't have to special case infinity. Int.MAX_VALUE is ~infinity for all practical
    // use cases.
    if (readTimeoutMillis == 0) {
      this.readTimeoutMillis = Integer.MAX_VALUE;
    } else {
      this.readTimeoutMillis = readTimeoutMillis;
    }
    this.redirectStrategy = redirectStrategy;
  }

  /** Returns the {@link UrlResponseInfo} for the request associated with this callback. */
  ListenableFuture<UrlResponseInfo> getUrlResponseInfo() {
    return headersFuture;
  }

  /**
   * Returns the OkHttp {@link Source} for the request associated with this callback.
   *
   * <p>Note that retrieving data from the {@code Source} instance might block further as the
   * response body is streamed.
   */
  ListenableFuture<Source> getBodySource() {
    return bodySourceFuture;
  }

  List<UrlResponseInfo> getUrlResponseInfoChain() {
    return Collections.unmodifiableList(urlResponseInfoChain);
  }

  @Override
  public void onRedirectReceived(
      UrlRequest urlRequest, UrlResponseInfo urlResponseInfo, String nextUrl) {
    // We shouldn't follow redirects - pass the given UrlResponseInfo as the ultimate result
    if (!redirectStrategy.followRedirects()) {
      checkState(headersFuture.set(urlResponseInfo));
      // Note: This might not match the content length headers but we have no way of accessing
      // the actual body with current Cronet's APIs (see RedirectStrategy).
      checkState(bodySourceFuture.set(new Buffer()));
      urlRequest.cancel();
      return;
    }

    // We should follow redirects and we haven't hit the cap yet
    urlResponseInfoChain.add(urlResponseInfo);
    if (urlResponseInfo.getUrlChain().size() <= redirectStrategy.numberOfRedirectsToFollow()) {
      urlRequest.followRedirect();
      return;
    }

    // Cap reached - cancel the request and fail. Exception crafted to match OkHttp.
    urlRequest.cancel();

    IOException e =
        new ProtocolException(
            "Too many follow-up requests: " + (redirectStrategy.numberOfRedirectsToFollow() + 1));
    headersFuture.setException(e);
    bodySourceFuture.setException(e);
  }

  @Override
  public void onResponseStarted(UrlRequest urlRequest, UrlResponseInfo urlResponseInfo) {
    request = urlRequest;

    checkState(headersFuture.set(urlResponseInfo));
    checkState(bodySourceFuture.set(new CronetBodySource()));
  }

  @Override
  public void onReadCompleted(
      UrlRequest urlRequest, UrlResponseInfo urlResponseInfo, ByteBuffer byteBuffer) {
    callbackResults.add(new CallbackResult(CallbackStep.ON_READ_COMPLETED, byteBuffer, null));
  }

  @Override
  public void onSucceeded(UrlRequest urlRequest, UrlResponseInfo urlResponseInfo) {
    callbackResults.add(new CallbackResult(CallbackStep.ON_SUCCESS, null, null));
  }

  @Override
  public void onFailed(UrlRequest urlRequest, UrlResponseInfo urlResponseInfo, CronetException e) {
    // If this was called before we start reading the body, the exception will
    // propagate in the future providing headers and the body wrapper.
    if (headersFuture.setException(e) && bodySourceFuture.setException(e)) {
      return;
    }

    // If this was called as a reaction to a read() call, the read result will propagate
    // the exception.
    callbackResults.add(new CallbackResult(CallbackStep.ON_FAILED, null, e));
  }

  @Override
  public void onCanceled(UrlRequest urlRequest, UrlResponseInfo responseInfo) {
    canceled.set(true);
    callbackResults.add(new CallbackResult(CallbackStep.ON_CANCELED, null, null));

    // If there's nobody listening it's possible that the cancellation happened before we even
    // received anything from the server. In that case inform the thread that's awaiting server
    // response about the cancellation as well. This becomes a no-op if the futures
    // were already set.
    IOException e = new IOException("The request was canceled!");
    headersFuture.setException(e);
    bodySourceFuture.setException(e);
  }

  private class CronetBodySource implements Source {

    private ByteBuffer buffer = ByteBuffer.allocateDirect(CRONET_BYTE_BUFFER_CAPACITY);

    /** Whether the close() method has been called. */
    private volatile boolean closed = false;

    @Override
    public long read(Buffer sink, long byteCount) throws IOException {
      if (canceled.get()) {
        throw new IOException("The request was canceled!");
      }

      // Using IAE instead of NPE (checkNotNull) for okio.RealBufferedSource consistency
      checkArgument(sink != null, "sink == null");
      checkArgument(byteCount >= 0, "byteCount < 0: %s", byteCount);
      checkState(!closed, "closed");

      if (finished.get()) {
        return -1;
      }

      if (byteCount < buffer.limit()) {
        buffer.limit((int) byteCount);
      }

      request.read(buffer);

      CallbackResult result;
      try {
        result = callbackResults.poll(readTimeoutMillis, MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        result = null;
      }

      if (result == null) {
        // Either readResult.poll() was interrupted or it timed out.
        request.cancel();
        throw new CronetTimeoutException();
      }

      switch (result.callbackStep) {
        // We null the buffer in final statuses to allow fast GC of the buffer even if the callback
        // is still in use.
        case ON_FAILED:
          finished.set(true);
          buffer = null;
          throw new IOException(result.exception);
        case ON_SUCCESS:
          finished.set(true);
          buffer = null;
          return -1;
        case ON_CANCELED:
          // The canceled flag is already set by the onCanceled method
          // so not setting it here.

          buffer = null;
          throw new IOException("The request was canceled!");
        case ON_READ_COMPLETED:
          result.buffer.flip();
          int bytesWritten = sink.write(result.buffer);
          result.buffer.clear();
          return bytesWritten;
      }

      throw new AssertionError("The switch block above is exhaustive!");
    }

    @Override
    public Timeout timeout() {
      // TODO(danstahr): This should likely respect the OkHttp timeout somehow
      return Timeout.NONE;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      if (!finished.get()) {
        request.cancel();
      }
    }
  }

  private static class CallbackResult {
    private final CallbackStep callbackStep;
    @Nullable private final ByteBuffer buffer;
    @Nullable private final CronetException exception;

    private CallbackResult(
        CallbackStep callbackStep,
        @Nullable ByteBuffer buffer,
        @Nullable CronetException exception) {
      this.callbackStep = callbackStep;
      this.buffer = buffer;
      this.exception = exception;
    }
  }

  private enum CallbackStep {
    ON_READ_COMPLETED,
    ON_SUCCESS,
    ON_FAILED,
    ON_CANCELED
  }
}
