/*
 * Copyright (C) 2026 Square, Inc.
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
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import kotlin.reflect.KClass
import okhttp3.CallEvent.CallStart
import okhttp3.CallEvent.Canceled
import okio.Timeout
import org.junit.jupiter.api.Test

/** Regression coverage for #9372 (cancel during EventListenerRelay handoff). */
class EventListenerRelayTest {
  @Test
  fun sequentialEventsAreRecordedOnceEach() {
    val call = RecordingCall()
    val eventRecorder = EventRecorder()
    call.addEventListener(EventListenerRelay(call, eventRecorder).eventListener)

    call.eventListener.callStart(call)
    call.eventListener.canceled(call)

    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      Canceled::class,
    )
  }

  /**
   * Cancel while the active relay is blocked installing its successor. The pre-fix one-shot relay
   * dropped [Canceled] in this window; the recorder must still see both events.
   */
  @Test
  fun cancelDuringSuccessorHandoffIsRecorded() {
    val startedSuccessorAdd = CountDownLatch(1)
    val finishSuccessorAdd = CountDownLatch(1)
    val addCount = AtomicInteger()

    val call =
      object : RecordingCall() {
        override fun addEventListener(eventListener: EventListener) {
          // Block successor installs only (not the initial relay registration).
          if (addCount.getAndIncrement() > 0) {
            startedSuccessorAdd.countDown()
            assertThat(finishSuccessorAdd.await(5, TimeUnit.SECONDS)).isTrue()
          }
          super.addEventListener(eventListener)
        }
      }

    val eventRecorder = EventRecorder()
    call.addEventListener(EventListenerRelay(call, eventRecorder).eventListener)

    val errors = AtomicReference<Throwable>()
    val callStartDone = CountDownLatch(1)
    Thread {
      try {
        call.eventListener.callStart(call)
      } catch (t: Throwable) {
        errors.compareAndSet(null, t)
      } finally {
        callStartDone.countDown()
      }
    }.start()

    assertThat(startedSuccessorAdd.await(5, TimeUnit.SECONDS)).isTrue()
    call.eventListener.canceled(call)
    finishSuccessorAdd.countDown()
    assertThat(callStartDone.await(5, TimeUnit.SECONDS)).isTrue()
    errors.get()?.let { throw it }

    val types = eventRecorder.recordedEventTypes()
    assertThat(types).contains(CallStart::class)
    assertThat(types).contains(Canceled::class)
    assertThat(types).hasSize(2)
  }

  open class RecordingCall : Call {
    @Volatile
    var eventListener: EventListener = EventListener.NONE

    override fun request(): Request = error("unexpected")

    override fun execute(): Response = error("unexpected")

    override fun enqueue(responseCallback: Callback): Unit = error("unexpected")

    override fun cancel(): Unit = error("unexpected")

    override fun isExecuted(): Boolean = error("unexpected")

    override fun isCanceled(): Boolean = error("unexpected")

    override fun timeout(): Timeout = error("unexpected")

    override fun addEventListener(eventListener: EventListener) {
      do {
        val previous = this.eventListener
      } while (!eventListenerUpdater.compareAndSet(this, previous, previous + eventListener))
    }

    override fun <T : Any> tag(type: KClass<T>): T? = error("unexpected")

    override fun <T> tag(type: Class<out T>): T? = error("unexpected")

    override fun <T : Any> tag(
      type: KClass<T>,
      computeIfAbsent: () -> T,
    ): T = error("unexpected")

    override fun <T : Any> tag(
      type: Class<T>,
      computeIfAbsent: () -> T,
    ): T = error("unexpected")

    override fun clone(): Call = error("unexpected")

    companion object {
      val eventListenerUpdater: AtomicReferenceFieldUpdater<RecordingCall, EventListener> =
        AtomicReferenceFieldUpdater.newUpdater(
          RecordingCall::class.java,
          EventListener::class.java,
          "eventListener",
        )
    }
  }
}
