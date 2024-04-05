/*
 * Copyright (C) 2023 Square, Inc.
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
package okhttp3

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slowish")
class DispatcherTest {
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()
  private val executor = RecordingExecutor(this)
  val callback = RecordingCallback()
  val webSocketListener =
    object : WebSocketListener() {
    }
  val dispatcher = Dispatcher(executor)
  val listener = RecordingEventListener()
  var client =
    clientTestRule.newClientBuilder()
      .dns { throw UnknownHostException() }
      .dispatcher(dispatcher)
      .eventListenerFactory(clientTestRule.wrap(listener))
      .build()

  @BeforeEach
  fun setUp() {
    dispatcher.maxRequests = 20
    dispatcher.maxRequestsPerHost = 10
    listener.forbidLock(dispatcher)
  }

  @Test
  fun maxRequestsZero() {
    assertFailsWith<IllegalArgumentException> {
      dispatcher.maxRequests = 0
    }
  }

  @Test
  fun maxPerHostZero() {
    assertFailsWith<IllegalArgumentException> {
      dispatcher.maxRequestsPerHost = 0
    }
  }

  @Test
  fun enqueuedJobsRunImmediately() {
    client.newCall(newRequest("http://a/1")).enqueue(callback)
    executor.assertJobs("http://a/1")
  }

  @Test
  fun maxRequestsEnforced() {
    dispatcher.maxRequests = 3
    client.newCall(newRequest("http://a/1")).enqueue(callback)
    client.newCall(newRequest("http://a/2")).enqueue(callback)
    client.newCall(newRequest("http://b/1")).enqueue(callback)
    client.newCall(newRequest("http://b/2")).enqueue(callback)
    executor.assertJobs("http://a/1", "http://a/2", "http://b/1")
  }

  @Test
  fun maxPerHostEnforced() {
    dispatcher.maxRequestsPerHost = 2
    client.newCall(newRequest("http://a/1")).enqueue(callback)
    client.newCall(newRequest("http://a/2")).enqueue(callback)
    client.newCall(newRequest("http://a/3")).enqueue(callback)
    executor.assertJobs("http://a/1", "http://a/2")
  }

  @Test
  fun maxPerHostNotEnforcedForWebSockets() {
    dispatcher.maxRequestsPerHost = 2
    client.newWebSocket(newRequest("http://a/1"), webSocketListener)
    client.newWebSocket(newRequest("http://a/2"), webSocketListener)
    client.newWebSocket(newRequest("http://a/3"), webSocketListener)
    executor.assertJobs("http://a/1", "http://a/2", "http://a/3")
  }

  @Test
  fun increasingMaxRequestsPromotesJobsImmediately() {
    dispatcher.maxRequests = 2
    client.newCall(newRequest("http://a/1")).enqueue(callback)
    client.newCall(newRequest("http://b/1")).enqueue(callback)
    client.newCall(newRequest("http://c/1")).enqueue(callback)
    client.newCall(newRequest("http://a/2")).enqueue(callback)
    client.newCall(newRequest("http://b/2")).enqueue(callback)
    dispatcher.maxRequests = 4
    executor.assertJobs("http://a/1", "http://b/1", "http://c/1", "http://a/2")
  }

  @Test
  fun increasingMaxPerHostPromotesJobsImmediately() {
    dispatcher.maxRequestsPerHost = 2
    client.newCall(newRequest("http://a/1")).enqueue(callback)
    client.newCall(newRequest("http://a/2")).enqueue(callback)
    client.newCall(newRequest("http://a/3")).enqueue(callback)
    client.newCall(newRequest("http://a/4")).enqueue(callback)
    client.newCall(newRequest("http://a/5")).enqueue(callback)
    dispatcher.maxRequestsPerHost = 4
    executor.assertJobs("http://a/1", "http://a/2", "http://a/3", "http://a/4")
  }

  @Test
  fun oldJobFinishesNewJobCanRunDifferentHost() {
    dispatcher.maxRequests = 1
    client.newCall(newRequest("http://a/1")).enqueue(callback)
    client.newCall(newRequest("http://b/1")).enqueue(callback)
    executor.finishJob("http://a/1")
    executor.assertJobs("http://b/1")
  }

  @Test
  fun oldJobFinishesNewJobWithSameHostStarts() {
    dispatcher.maxRequests = 2
    dispatcher.maxRequestsPerHost = 1
    client.newCall(newRequest("http://a/1")).enqueue(callback)
    client.newCall(newRequest("http://b/1")).enqueue(callback)
    client.newCall(newRequest("http://b/2")).enqueue(callback)
    client.newCall(newRequest("http://a/2")).enqueue(callback)
    executor.finishJob("http://a/1")
    executor.assertJobs("http://b/1", "http://a/2")
  }

  @Test
  fun oldJobFinishesNewJobCantRunDueToHostLimit() {
    dispatcher.maxRequestsPerHost = 1
    client.newCall(newRequest("http://a/1")).enqueue(callback)
    client.newCall(newRequest("http://b/1")).enqueue(callback)
    client.newCall(newRequest("http://a/2")).enqueue(callback)
    executor.finishJob("http://b/1")
    executor.assertJobs("http://a/1")
  }

  @Test
  fun enqueuedCallsStillRespectMaxCallsPerHost() {
    dispatcher.maxRequests = 1
    dispatcher.maxRequestsPerHost = 1
    client.newCall(newRequest("http://a/1")).enqueue(callback)
    client.newCall(newRequest("http://b/1")).enqueue(callback)
    client.newCall(newRequest("http://b/2")).enqueue(callback)
    client.newCall(newRequest("http://b/3")).enqueue(callback)
    dispatcher.maxRequests = 3
    executor.finishJob("http://a/1")
    executor.assertJobs("http://b/1")
  }

  @Test
  fun cancelingRunningJobTakesNoEffectUntilJobFinishes() {
    dispatcher.maxRequests = 1
    val c1 = client.newCall(newRequest("http://a/1", "tag1"))
    val c2 = client.newCall(newRequest("http://a/2"))
    c1.enqueue(callback)
    c2.enqueue(callback)
    c1.cancel()
    executor.assertJobs("http://a/1")
    executor.finishJob("http://a/1")
    executor.assertJobs("http://a/2")
  }

  @Test
  fun asyncCallAccessors() {
    dispatcher.maxRequests = 3
    val a1 = client.newCall(newRequest("http://a/1"))
    val a2 = client.newCall(newRequest("http://a/2"))
    val a3 = client.newCall(newRequest("http://a/3"))
    val a4 = client.newCall(newRequest("http://a/4"))
    val a5 = client.newCall(newRequest("http://a/5"))
    a1.enqueue(callback)
    a2.enqueue(callback)
    a3.enqueue(callback)
    a4.enqueue(callback)
    a5.enqueue(callback)
    assertThat(dispatcher.runningCallsCount()).isEqualTo(3)
    assertThat(dispatcher.queuedCallsCount()).isEqualTo(2)
    assertThat(dispatcher.runningCalls())
      .containsExactlyInAnyOrder(a1, a2, a3)
    assertThat(dispatcher.queuedCalls())
      .containsExactlyInAnyOrder(a4, a5)
  }

  @Test
  fun synchronousCallAccessors() {
    val ready = CountDownLatch(2)
    val waiting = CountDownLatch(1)
    client =
      client.newBuilder()
        .addInterceptor(
          Interceptor { chain: Interceptor.Chain? ->
            try {
              ready.countDown()
              waiting.await()
            } catch (e: InterruptedException) {
              throw AssertionError()
            }
            throw IOException()
          },
        )
        .build()
    val a1 = client.newCall(newRequest("http://a/1"))
    val a2 = client.newCall(newRequest("http://a/2"))
    val a3 = client.newCall(newRequest("http://a/3"))
    val a4 = client.newCall(newRequest("http://a/4"))
    val t1 = makeSynchronousCall(a1)
    val t2 = makeSynchronousCall(a2)

    // We created 4 calls and started 2 of them. That's 2 running calls and 0 queued.
    ready.await()
    assertThat(dispatcher.runningCallsCount()).isEqualTo(2)
    assertThat(dispatcher.queuedCallsCount()).isEqualTo(0)
    assertThat(dispatcher.runningCalls())
      .containsExactlyInAnyOrder(a1, a2)
    assertThat(dispatcher.queuedCalls()).isEmpty()

    // Cancel some calls. That doesn't impact running or queued.
    a2.cancel()
    a3.cancel()
    assertThat(dispatcher.runningCalls())
      .containsExactlyInAnyOrder(a1, a2)
    assertThat(dispatcher.queuedCalls()).isEmpty()

    // Let the calls finish.
    waiting.countDown()
    t1.join()
    t2.join()

    // Now we should have 0 running calls and 0 queued calls.
    assertThat(dispatcher.runningCallsCount()).isEqualTo(0)
    assertThat(dispatcher.queuedCallsCount()).isEqualTo(0)
    assertThat(dispatcher.runningCalls()).isEmpty()
    assertThat(dispatcher.queuedCalls()).isEmpty()
    assertThat(a1.isExecuted()).isTrue()
    assertThat(a1.isCanceled()).isFalse()
    assertThat(a2.isExecuted()).isTrue()
    assertThat(a2.isCanceled()).isTrue()
    assertThat(a3.isExecuted()).isFalse()
    assertThat(a3.isCanceled()).isTrue()
    assertThat(a4.isExecuted()).isFalse()
    assertThat(a4.isCanceled()).isFalse()
  }

  @Test
  fun idleCallbackInvokedWhenIdle() {
    val idle = AtomicBoolean()
    dispatcher.idleCallback = Runnable { idle.set(true) }
    client.newCall(newRequest("http://a/1")).enqueue(callback)
    client.newCall(newRequest("http://a/2")).enqueue(callback)
    executor.finishJob("http://a/1")
    assertThat(idle.get()).isFalse()
    val ready = CountDownLatch(1)
    val proceed = CountDownLatch(1)
    client =
      client.newBuilder()
        .addInterceptor(
          Interceptor { chain: Interceptor.Chain ->
            ready.countDown()
            try {
              proceed.await(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
              throw RuntimeException(e)
            }
            chain.proceed(chain.request())
          },
        )
        .build()
    val t1 = makeSynchronousCall(client.newCall(newRequest("http://a/3")))
    ready.await(5, TimeUnit.SECONDS)
    executor.finishJob("http://a/2")
    assertThat(idle.get()).isFalse()
    proceed.countDown()
    t1.join()
    assertThat(idle.get()).isTrue()
  }

  @Test
  fun executionRejectedImmediately() {
    val request = newRequest("http://a/1")
    executor.shutdown()
    client.newCall(request).enqueue(callback)
    callback.await(request.url).assertFailure(InterruptedIOException::class.java)
    assertThat(listener.recordedEventTypes())
      .containsExactly("CallStart", "CallFailed")
  }

  @Test
  fun executionRejectedAfterMaxRequestsChange() {
    val request1 = newRequest("http://a/1")
    val request2 = newRequest("http://a/2")
    dispatcher.maxRequests = 1
    client.newCall(request1).enqueue(callback)
    executor.shutdown()
    client.newCall(request2).enqueue(callback)
    dispatcher.maxRequests = 2 // Trigger promotion.
    callback.await(request2.url).assertFailure(InterruptedIOException::class.java)
    assertThat(listener.recordedEventTypes())
      .containsExactly("CallStart", "CallStart", "CallFailed")
  }

  @Test
  fun executionRejectedAfterMaxRequestsPerHostChange() {
    val request1 = newRequest("http://a/1")
    val request2 = newRequest("http://a/2")
    dispatcher.maxRequestsPerHost = 1
    client.newCall(request1).enqueue(callback)
    executor.shutdown()
    client.newCall(request2).enqueue(callback)
    dispatcher.maxRequestsPerHost = 2 // Trigger promotion.
    callback.await(request2.url).assertFailure(InterruptedIOException::class.java)
    assertThat(listener.recordedEventTypes())
      .containsExactly("CallStart", "CallStart", "CallFailed")
  }

  @Test
  fun executionRejectedAfterPrecedingCallFinishes() {
    val request1 = newRequest("http://a/1")
    val request2 = newRequest("http://a/2")
    dispatcher.maxRequests = 1
    client.newCall(request1).enqueue(callback)
    executor.shutdown()
    client.newCall(request2).enqueue(callback)
    executor.finishJob("http://a/1") // Trigger promotion.
    callback.await(request2.url).assertFailure(InterruptedIOException::class.java)
    assertThat(listener.recordedEventTypes())
      .containsExactly("CallStart", "CallStart", "CallFailed")
  }

  private fun makeSynchronousCall(call: Call): Thread {
    val thread =
      Thread {
        try {
          call.execute()
          throw AssertionError()
        } catch (expected: IOException) {
        }
      }
    thread.start()
    return thread
  }

  private fun newRequest(url: String): Request {
    return Request.Builder().url(url).build()
  }

  private fun newRequest(
    url: String,
    tag: String,
  ): Request {
    return Request.Builder().url(url).tag(tag).build()
  }
}
