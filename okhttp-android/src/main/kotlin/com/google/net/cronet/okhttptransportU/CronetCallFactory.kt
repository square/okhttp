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

import android.net.http.HttpEngine
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.AsyncTimeout
import okio.Timeout

/** A [Call.Factory] implementation using Cronet as the transport layer.  */
@RequiresApi(34)
class CronetCallFactory private constructor(
  converter: RequestResponseConverter,
  responseCallbackExecutor: ExecutorService,
  readTimeoutMillis: Int,
  writeTimeoutMillis: Int,
  callTimeoutMillis: Int) : Call.Factory {
  private val converter: RequestResponseConverter
  private val responseCallbackExecutor: ExecutorService
  private val readTimeoutMillis: Int
  private val writeTimeoutMillis: Int
  private val callTimeoutMillis: Int

  init {
    check(readTimeoutMillis >= 0) {
      "Read timeout mustn't be negative!"
    }
    check(writeTimeoutMillis >= 0) {
      "Write timeout mustn't be negative!"
    }
    check(callTimeoutMillis >= 0) {
      "Call timeout mustn't be negative!"
    }
    this.converter = converter
    this.responseCallbackExecutor = responseCallbackExecutor
    this.readTimeoutMillis = readTimeoutMillis
    this.writeTimeoutMillis = writeTimeoutMillis
    this.callTimeoutMillis = callTimeoutMillis
  }

  override fun newCall(request: Request): Call {
    return CronetCall(request, this, converter, responseCallbackExecutor)
  }

  private class CronetCall(
    private val okHttpRequest: Request,
    private val motherFactory: CronetCallFactory,
    private val converter: RequestResponseConverter,
    private val responseCallbackExecutor: ExecutorService) : Call {
    private val executed = AtomicBoolean()
    private val canceled = AtomicBoolean()
    private val convertedRequestAndResponse = AtomicReference<RequestResponseConverter.CronetRequestAndOkHttpResponse>()
    val timeout: AsyncTimeout

    init {
      timeout = object : AsyncTimeout() {
        override fun timedOut() {
          cancel()
        }
      }
      timeout.timeout(motherFactory.callTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
    }

    override fun request(): Request {
      return okHttpRequest
    }

    @Throws(IOException::class)
    override fun execute(): Response {
      evaluateExecutionPreconditions()
      return try {
        timeout.enter()
        val requestAndOkHttpResponse = converter.convert(
          request(), motherFactory.readTimeoutMillis, motherFactory.writeTimeoutMillis)
        convertedRequestAndResponse.set(requestAndOkHttpResponse)
        startRequestIfNotCanceled()
        toCronetCallFactoryResponse(this, requestAndOkHttpResponse.response)
      } catch (e: RuntimeException) {
        timeout.exit()
        throw e
      } catch (e: IOException) {
        timeout.exit()
        throw e
      }
    }

    override fun enqueue(responseCallback: Callback) {
      try {
        timeout.enter()
        evaluateExecutionPreconditions()
        val requestAndOkHttpResponse = converter.convert(
          request(), motherFactory.readTimeoutMillis, motherFactory.writeTimeoutMillis)
        convertedRequestAndResponse.set(requestAndOkHttpResponse)
        val call = this
        Futures.addCallback(
          requestAndOkHttpResponse.responseAsync,
          object : FutureCallback<Response> {
            override fun onSuccess(result: Response) {
              try {
                responseCallback.onResponse(call, toCronetCallFactoryResponse(call, result))
              } catch (e: IOException) {
                // The call factory doesn't really mind this - the application code
                // threw an exception while handling the response, they should have taken care
                // of it. Just logging the error is consistent with plain OkHttp implementation.
                Log.i(TAG, "Callback failure for " + toLoggableString(), e)
              }
            }

            override fun onFailure(t: Throwable) {
              if (t is IOException) {
                responseCallback.onFailure(call, t)
              } else {
                responseCallback.onFailure(call, IOException(t))
              }
            }
          },
          responseCallbackExecutor)
        startRequestIfNotCanceled()
      } catch (e: IOException) {
        timeout.exit()
        responseCallback.onFailure(this, e)
      }
    }

    override fun clone(): Call {
      return motherFactory.newCall(request())
    }

    override fun cancel() {
      if (canceled.getAndSet(true)) {
        // already canceled
        return
      }
      val localConverted = convertedRequestAndResponse.get()
      localConverted?.request?.cancel() // else the cancel signal will be picked up by the execute() / enqueue() methods.
    }

    override fun isExecuted(): Boolean {
      return executed.get()
    }

    override fun isCanceled(): Boolean {
      return canceled.get()
    }

    override fun timeout(): Timeout {
      return timeout
    }

    private fun toLoggableString(): String {
      return "call to " + request().url.redact()
    }

    /**
     * Verifies that the call can be executed and sets the state of the call to "being executed".
     *
     * @throws IllegalStateException if the request has already been executed.
     * @throws IOException if the request was canceled
     */
    @Throws(IOException::class)
    private fun evaluateExecutionPreconditions() {
      if (canceled.get()) {
        throw IOException("Can't execute canceled requests")
      }
      check(!executed.getAndSet(true)) {
        "Already Executed"
      }
    }

    private fun startRequestIfNotCanceled() {
      val requestAndOkHttpResponse = convertedRequestAndResponse.get()
      check(requestAndOkHttpResponse != null) {
        "convertedRequestAndResponse must be set!"
      }

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
        requestAndOkHttpResponse.request.cancel()
      } else {
        requestAndOkHttpResponse.request.start()
      }
    }
  }

  class Builder internal constructor(cronetEngine: HttpEngine) : RequestResponseConverterBasedBuilder<Builder, CronetCallFactory>(cronetEngine) {
    private var readTimeoutMillis = DEFAULT_READ_WRITE_TIMEOUT_MILLIS
    private var writeTimeoutMillis = DEFAULT_READ_WRITE_TIMEOUT_MILLIS
    private var callTimeoutMillis = 0 // No timeout
    private var callbackExecutorService: ExecutorService? = null
    fun setReadTimeoutMillis(readTimeoutMillis: Int): Builder {
      check(readTimeoutMillis >= 0) { "Read timeout mustn't be negative!" }
      this.readTimeoutMillis = readTimeoutMillis
      return this
    }

    fun setWriteTimeoutMillis(writeTimeoutMillis: Int): Builder {
      check(writeTimeoutMillis >= 0) {
        "Write timeout mustn't be negative!"
      }
      this.writeTimeoutMillis = writeTimeoutMillis
      return this
    }

    fun setCallbackExecutorService(callbackExecutorService: ExecutorService?): Builder {
      checkNotNull(callbackExecutorService)
      this.callbackExecutorService = callbackExecutorService
      return this
    }

    fun setCallTimeoutMillis(callTimeoutMillis: Int): Builder {
      check(callTimeoutMillis >= 0) {
        "Call timeout mustn't be negative!"
      }
      this.callTimeoutMillis = callTimeoutMillis
      return this
    }

    override fun build(converter: RequestResponseConverter): CronetCallFactory {
      // Consistent with OkHttp impl
      val localCallbackExecutorService = callbackExecutorService ?: Executors.newCachedThreadPool()
      return CronetCallFactory(
        converter,
        localCallbackExecutorService,
        readTimeoutMillis,
        writeTimeoutMillis,
        callTimeoutMillis)
    }

    companion object {
      private const val DEFAULT_READ_WRITE_TIMEOUT_MILLIS = 10000
    }
  }

  companion object {
    private const val TAG = "CronetCallFactory"
    fun newBuilder(cronetEngine: HttpEngine): Builder {
      return Builder(cronetEngine)
    }

    private fun toCronetCallFactoryResponse(call: CronetCall, response: Response): Response {
      checkNotNull(response.body)
      return response
        .newBuilder()
        .body(
          object : CronetTransportResponseBody(response.body) {
            override fun customCloseHook() {
              call.timeout.exit()
            }
          })
        .build()
    }
  }
}
