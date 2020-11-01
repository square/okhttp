/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal.concurrent

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Integration test to confirm that [TaskRunner] works with a real backend. Business logic is all
 * exercised by [TaskRunnerTest].
 *
 * This test is doing real sleeping with tolerances of 250 ms. Hopefully that's enough for even the
 * busiest of CI servers.
 */
class TaskRunnerRealBackendTest {
  private val log = LinkedBlockingDeque<String>()

  private val loggingUncaughtExceptionHandler = UncaughtExceptionHandler { _, throwable ->
    log.put("uncaught exception: $throwable")
  }

  private val threadFactory = ThreadFactory { runnable ->
    Thread(runnable, "TaskRunnerRealBackendTest").apply {
      isDaemon = true
      uncaughtExceptionHandler = loggingUncaughtExceptionHandler
    }
  }

  private val backend = TaskRunner.RealBackend(threadFactory)
  private val taskRunner = TaskRunner(backend)
  private val queue = taskRunner.newQueue()

  @AfterEach fun tearDown() {
    backend.shutdown()
  }

  @Test fun test() {
    val t1 = System.nanoTime() / 1e6

    val delays = mutableListOf(TimeUnit.MILLISECONDS.toNanos(1000), -1L)
    queue.schedule("task", TimeUnit.MILLISECONDS.toNanos(750)) {
      log.put("runOnce delays.size=${delays.size}")
      return@schedule delays.removeAt(0)
    }

    assertThat(log.take()).isEqualTo("runOnce delays.size=2")
    val t2 = System.nanoTime() / 1e6 - t1
    assertThat(t2).isCloseTo(750.0, Offset.offset(250.0))

    assertThat(log.take()).isEqualTo("runOnce delays.size=1")
    val t3 = System.nanoTime() / 1e6 - t1
    assertThat(t3).isCloseTo(1750.0, Offset.offset(250.0))
  }

  @Test fun taskFailsWithUncheckedException() {
    queue.schedule("task", TimeUnit.MILLISECONDS.toNanos(100)) {
      log.put("failing task running")
      throw RuntimeException("boom!")
    }

    queue.schedule("task", TimeUnit.MILLISECONDS.toNanos(200)) {
      log.put("normal task running")
      return@schedule -1L
    }

    queue.idleLatch().await(500, TimeUnit.MILLISECONDS)

    assertThat(log.take()).isEqualTo("failing task running")
    assertThat(log.take()).isEqualTo("uncaught exception: java.lang.RuntimeException: boom!")
    assertThat(log.take()).isEqualTo("normal task running")
    assertThat(log).isEmpty()
  }

  @Test fun idleLatchAfterShutdown() {
    queue.schedule("task") {
      Thread.sleep(250)
      backend.shutdown()
      return@schedule -1L
    }

    assertThat(queue.idleLatch().await(500L, TimeUnit.MILLISECONDS)).isTrue()
    assertThat(queue.idleLatch().count).isEqualTo(0)
  }
}
