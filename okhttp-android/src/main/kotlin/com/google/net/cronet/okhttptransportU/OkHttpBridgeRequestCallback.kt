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

import android.net.http.HttpException
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.ProtocolException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import okio.Buffer
import okio.Source
import okio.Timeout

/**
 * An implementation of Cronet's callback. This is the heart of the bridge and deals with most of
 * the async-sync paradigm translation.
 *
 *
 * Translating the UrlResponseInfo is relatively straightforward as the entire object is
 * available immediately and is relatively small, so it can easily fit in memory.
 *
 *
 * Translating the body is a bit more tricky because of the mismatch between OkHttp and Cronet
 * designs. We invoke Cronet's read and wait for the result using synchronization primitives (see
 * BodySource implementation). The implementation is assuming that there's always at most one read()
 * request in flight (which is safe to assume), and relies on reasonable fairness of thread
 * scheduling, especially when handling cancellations.
 */
@RequiresApi(34)
class OkHttpBridgeRequestCallback(private val readTimeoutMillis: Long, private val redirectStrategy: RedirectStrategy) : UrlRequest.Callback() {
  /** A bridge between Cronet's asynchronous callbacks and OkHttp's blocking stream-like reads.  */
  private val bodySourceFuture = CompletableDeferred<Source>()

  /** Signal whether the request is finished and the response has been fully read.  */
  private val finished = AtomicBoolean(false)

  /** Signal whether the request was canceled.  */
  private val canceled = AtomicBoolean(false)

  /**
   * An internal, blocking, thread safe way of passing data between the callback methods and [ ][.bodySourceFuture].
   *
   *
   * Has a capacity of 2 - at most one slot for a read result and at most 1 slot for cancellation
   * signal, this guarantees that all inserts are non blocking.
   */
  private val callbackResults: BlockingQueue<CallbackResult> = ArrayBlockingQueue(2)

  /** The response headers.  */
  private val _headersFuture = CompletableDeferred<UrlResponseInfo>()

  /** The previous responses as reported to [.onRedirectReceived], from oldest to newest. *  */
  private val _urlResponseInfoChain: MutableList<UrlResponseInfo> = ArrayList()

  /** The request being processed. Set when the request is first seen by the callback.  */
  @Volatile
  private var request: UrlRequest? = null

  init {
    check(readTimeoutMillis >= 0)
  }

  val urlResponseInfo: Deferred<UrlResponseInfo>
    /** Returns the [UrlResponseInfo] for the request associated with this callback.  */
    get() = _headersFuture

  val bodySource: CompletableDeferred<Source>
    /**
     * Returns the OkHttp [Source] for the request associated with this callback.
     *
     *
     * Note that retrieving data from the `Source` instance might block further as the
     * response body is streamed.
     */
    get() = bodySourceFuture

  val urlResponseInfoChain: List<UrlResponseInfo>
    get() = Collections.unmodifiableList(_urlResponseInfoChain)

  override fun onRedirectReceived(
    urlRequest: UrlRequest, urlResponseInfo: UrlResponseInfo, nextUrl: String) {
    // We shouldn't follow redirects - pass the given UrlResponseInfo as the ultimate result
    if (!redirectStrategy.followRedirects()) {
      check(_headersFuture.complete(urlResponseInfo))
      // Note: This might not match the content length headers but we have no way of accessing
      // the actual body with current Cronet's APIs (see RedirectStrategy).
      check(bodySourceFuture.complete(Buffer()))
      urlRequest.cancel()
      return
    }

    // We should follow redirects and we haven't hit the cap yet
    _urlResponseInfoChain.add(urlResponseInfo)
    if (urlResponseInfo.urlChain.size <= redirectStrategy.numberOfRedirectsToFollow()) {
      urlRequest.followRedirect()
      return
    }

    // Cap reached - cancel the request and fail. Exception crafted to match OkHttp.
    urlRequest.cancel()
    val e: IOException = ProtocolException(
      "Too many follow-up requests: " + (redirectStrategy.numberOfRedirectsToFollow() + 1))
    _headersFuture.completeExceptionally(e)
    bodySourceFuture.completeExceptionally(e)
  }

  override fun onResponseStarted(urlRequest: UrlRequest, urlResponseInfo: UrlResponseInfo) {
    request = urlRequest
    check(_headersFuture.complete(urlResponseInfo))
    check(bodySourceFuture.complete(CronetBodySource()))
  }

  override fun onReadCompleted(
    urlRequest: UrlRequest, urlResponseInfo: UrlResponseInfo, byteBuffer: ByteBuffer) {
    callbackResults.add(CallbackResult(CallbackStep.ON_READ_COMPLETED, byteBuffer, null))
  }

  override fun onSucceeded(urlRequest: UrlRequest, urlResponseInfo: UrlResponseInfo) {
    callbackResults.add(CallbackResult(CallbackStep.ON_SUCCESS, null, null))
  }

  override fun onFailed(urlRequest: UrlRequest, urlResponseInfo: UrlResponseInfo, e: HttpException) {
    // If this was called before we start reading the body, the exception will
    // propagate in the future providing headers and the body wrapper.
    if (_headersFuture.completeExceptionally(e) && bodySourceFuture.completeExceptionally(e)) {
      return
    }

    // If this was called as a reaction to a read() call, the read result will propagate
    // the exception.
    callbackResults.add(CallbackResult(CallbackStep.ON_FAILED, null, e))
  }

  override fun onCanceled(urlRequest: UrlRequest, responseInfo: UrlResponseInfo) {
    canceled.set(true)
    callbackResults.add(CallbackResult(CallbackStep.ON_CANCELED, null, null))

    // If there's nobody listening it's possible that the cancellation happened before we even
    // received anything from the server. In that case inform the thread that's awaiting server
    // response about the cancellation as well. This becomes a no-op if the futures
    // were already set.
    val e = IOException("The request was canceled!")
    _headersFuture.completeExceptionally(e)
    bodySourceFuture.completeExceptionally(e)
  }

  private inner class CronetBodySource : Source {
    private var buffer = ByteBuffer.allocateDirect(CRONET_BYTE_BUFFER_CAPACITY)

    /** Whether the close() method has been called.  */
    @Volatile
    private var closed = false

    override fun read(sink: Buffer, byteCount: Long): Long {
      if (canceled.get()) {
        throw IOException("The request was canceled!")
      }


      check(byteCount >= 0) { "byteCount < 0: $byteCount" }
      check(!closed) {
        "closed"
      }
      if (finished.get()) {
        return -1
      }
      if (byteCount < buffer.limit()) {
        buffer.limit(byteCount.toInt())
      }
      request!!.read(buffer)
      val result: CallbackResult? = try {
        // So that we don't have to special case infinity. Int.MAX_VALUE is ~infinity for all practical
        // use cases.
        val effectiveTimeout = readTimeoutMillis.timeoutOrMax

        callbackResults.poll(effectiveTimeout, TimeUnit.MILLISECONDS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
      }
      if (result == null) {
        // Either readResult.poll() was interrupted or it timed out.
        request!!.cancel()
        throw CronetTimeoutException()
      }
      return when (result.callbackStep) {
        CallbackStep.ON_FAILED -> {
          finished.set(true)
          buffer = null
          throw IOException(result.exception)
        }

        CallbackStep.ON_SUCCESS -> {
          finished.set(true)
          buffer = null
          -1
        }

        CallbackStep.ON_CANCELED -> {
          // The canceled flag is already set by the onCanceled method
          // so not setting it here.
          buffer = null
          throw IOException("The request was canceled!")
        }

        CallbackStep.ON_READ_COMPLETED -> {
          result.buffer!!.flip()
          val bytesWritten = sink.write(result.buffer)
          result.buffer.clear()
          bytesWritten.toLong()
        }
      }
    }

    override fun timeout(): Timeout {
      // TODO(danstahr): This should likely respect the OkHttp timeout somehow
      return Timeout.NONE
    }

    override fun close() {
      if (closed) {
        return
      }
      closed = true
      if (!finished.get()) {
        request!!.cancel()
      }
    }
  }

  private class CallbackResult(
    val callbackStep: CallbackStep,
    val buffer: ByteBuffer?,
    val exception: HttpException?)

  private enum class CallbackStep {
    ON_READ_COMPLETED,
    ON_SUCCESS,
    ON_FAILED,
    ON_CANCELED
  }

  companion object {
    /**
     * The byte buffer capacity for reading Cronet response bodies. Each response callback will
     * allocate its own buffer of this size once the response starts being processed.
     */
    private const val CRONET_BYTE_BUFFER_CAPACITY = 32 * 1024

    internal val Long.timeoutOrMax: Long
      get() = if (this == 0L) {
        Long.MAX_VALUE
      } else {
        this
      }
  }
}
