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

import java.util.concurrent.RejectedExecutionException
import okhttp3.TestLogHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.fail

class TaskRunnerTest {
  @RegisterExtension @JvmField val testLogHandler = TestLogHandler(TaskRunner::class.java)

  private val taskFaker = TaskFaker()
  private val taskRunner = taskFaker.taskRunner
  private val log = mutableListOf<String>()
  private val redQueue = taskRunner.newQueue()
  private val blueQueue = taskRunner.newQueue()
  private val greenQueue = taskRunner.newQueue()

  @Test fun executeDelayed() {
    redQueue.execute("task", 100.µs) {
      log += "run@${taskFaker.nanoTime}"
    }

    taskFaker.advanceUntil(0.µs)
    assertThat(log).containsExactly()

    taskFaker.advanceUntil(99.µs)
    assertThat(log).containsExactly()

    taskFaker.advanceUntil(100.µs)
    assertThat(log).containsExactly("run@100000")

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 finished run in   0 µs: task"
    )
  }

  @Test fun executeRepeated() {
    val delays = mutableListOf(50.µs, 150.µs, -1L)
    redQueue.schedule("task", 100.µs) {
      log += "run@${taskFaker.nanoTime}"
      return@schedule delays.removeAt(0)
    }

    taskFaker.advanceUntil(0.µs)
    assertThat(log).containsExactly()

    taskFaker.advanceUntil(100.µs)
    assertThat(log).containsExactly("run@100000")

    taskFaker.advanceUntil(150.µs)
    assertThat(log).containsExactly("run@100000", "run@150000")

    taskFaker.advanceUntil(299.µs)
    assertThat(log).containsExactly("run@100000", "run@150000")

    taskFaker.advanceUntil(300.µs)
    assertThat(log).containsExactly("run@100000", "run@150000", "run@300000")

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 run again after  50 µs: task",
        "FINE: Q10000 finished run in   0 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 run again after 150 µs: task",
        "FINE: Q10000 finished run in   0 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 finished run in   0 µs: task"
    )
  }

  /** Repeat with a delay of 200 but schedule with a delay of 50. The schedule wins. */
  @Test fun executeScheduledEarlierReplacesRepeatedLater() {
    val task = object : Task("task") {
      val schedules = mutableListOf(50.µs)
      val delays = mutableListOf(200.µs, -1)
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        if (schedules.isNotEmpty()) {
          redQueue.schedule(this, schedules.removeAt(0))
        }
        return delays.removeAt(0)
      }
    }
    redQueue.schedule(task, 100.µs)

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100.µs)
    assertThat(log).containsExactly("run@100000")

    taskFaker.advanceUntil(150.µs)
    assertThat(log).containsExactly("run@100000", "run@150000")

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 scheduled after  50 µs: task",
        "FINE: Q10000 already scheduled     : task",
        "FINE: Q10000 finished run in   0 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 finished run in   0 µs: task"
    )
  }

  /** Schedule with a delay of 200 but repeat with a delay of 50. The repeat wins. */
  @Test fun executeRepeatedEarlierReplacesScheduledLater() {
    val task = object : Task("task") {
      val schedules = mutableListOf(200.µs)
      val delays = mutableListOf(50.µs, -1L)
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        if (schedules.isNotEmpty()) {
          redQueue.schedule(this, schedules.removeAt(0))
        }
        return delays.removeAt(0)
      }
    }
    redQueue.schedule(task, 100.µs)

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100.µs)
    assertThat(log).containsExactly("run@100000")

    taskFaker.advanceUntil(150.µs)
    assertThat(log).containsExactly("run@100000", "run@150000")

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 scheduled after 200 µs: task",
        "FINE: Q10000 run again after  50 µs: task",
        "FINE: Q10000 finished run in   0 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 finished run in   0 µs: task"
    )
  }

  @Test fun cancelReturnsTruePreventsNextExecution() {
    redQueue.execute("task", 100.µs) {
      log += "run@${taskFaker.nanoTime}"
    }

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    redQueue.cancelAll()

    taskFaker.advanceUntil(99.µs)
    assertThat(log).isEmpty()

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task",
        "FINE: Q10000 canceled              : task"
    )
  }

  @Test fun cancelReturnsFalseDoesNotCancel() {
    redQueue.schedule(object : Task("task", cancelable = false) {
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        return -1L
      }
    }, 100.µs)

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    redQueue.cancelAll()

    taskFaker.advanceUntil(99.µs)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100.µs)
    assertThat(log).containsExactly("run@100000")

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 finished run in   0 µs: task"
    )
  }

  @Test fun cancelWhileExecutingPreventsRepeat() {
    redQueue.schedule("task", 100.µs) {
      log += "run@${taskFaker.nanoTime}"
      redQueue.cancelAll()
      return@schedule 100.µs
    }

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100.µs)
    assertThat(log).containsExactly("run@100000")

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 finished run in   0 µs: task"
    )
  }

  @Test fun cancelWhileExecutingDoesNothingIfTaskDoesNotRepeat() {
    redQueue.execute("task", 100.µs) {
      log += "run@${taskFaker.nanoTime}"
      redQueue.cancelAll()
    }

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100.µs)
    assertThat(log).containsExactly("run@100000")

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 finished run in   0 µs: task"
    )
  }

  @Test fun cancelWhileExecutingDoesNotStopUncancelableTask() {
    redQueue.schedule(object : Task("task", cancelable = false) {
      val delays = mutableListOf(50.µs, -1L)
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        redQueue.cancelAll()
        return delays.removeAt(0)
      }
    }, 100.µs)

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100.µs)
    assertThat(log).containsExactly("run@100000")

    taskFaker.advanceUntil(150.µs)
    assertThat(log).containsExactly("run@100000", "run@150000")

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 run again after  50 µs: task",
        "FINE: Q10000 finished run in   0 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 finished run in   0 µs: task"
    )
  }

  @Test fun interruptingCoordinatorAttemptsToCancelsAndSucceeds() {
    redQueue.execute("task", 100.µs) {
      log += "run@${taskFaker.nanoTime}"
    }

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    taskFaker.interruptCoordinatorThread()

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task",
        "FINE: Q10000 canceled              : task"
    )
  }

  @Test fun interruptingCoordinatorAttemptsToCancelsAndFails() {
    redQueue.schedule(object : Task("task", cancelable = false) {
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        return -1L
      }
    }, 100.µs)

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    taskFaker.interruptCoordinatorThread()

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100.µs)
    assertThat(log).containsExactly("run@100000")

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 finished run in   0 µs: task"
    )
  }

  /** Inspect how many runnables have been enqueued. If none then we're truly sequential. */
  @Test fun singleQueueIsSerial() {
    redQueue.execute("task one", 100.µs) {
      log += "one:run@${taskFaker.nanoTime} parallel=${taskFaker.isParallel}"
    }

    redQueue.execute("task two", 100.µs) {
      log += "two:run@${taskFaker.nanoTime} parallel=${taskFaker.isParallel}"
    }

    redQueue.execute("task three", 100.µs) {
      log += "three:run@${taskFaker.nanoTime} parallel=${taskFaker.isParallel}"
    }

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100.µs)
    assertThat(log).containsExactly(
        "one:run@100000 parallel=false",
        "two:run@100000 parallel=false",
        "three:run@100000 parallel=false"
    )

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task one",
        "FINE: Q10000 scheduled after 100 µs: task two",
        "FINE: Q10000 scheduled after 100 µs: task three",
        "FINE: Q10000 starting              : task one",
        "FINE: Q10000 finished run in   0 µs: task one",
        "FINE: Q10000 starting              : task two",
        "FINE: Q10000 finished run in   0 µs: task two",
        "FINE: Q10000 starting              : task three",
        "FINE: Q10000 finished run in   0 µs: task three"
    )
  }

  /** Inspect how many runnables have been enqueued. If non-zero then we're truly parallel. */
  @Test fun differentQueuesAreParallel() {
    redQueue.execute("task one", 100.µs) {
      log += "one:run@${taskFaker.nanoTime} parallel=${taskFaker.isParallel}"
    }

    blueQueue.execute("task two", 100.µs) {
      log += "two:run@${taskFaker.nanoTime} parallel=${taskFaker.isParallel}"
    }

    greenQueue.execute("task three", 100.µs) {
      log += "three:run@${taskFaker.nanoTime} parallel=${taskFaker.isParallel}"
    }

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100.µs)
    assertThat(log).containsExactly(
        "one:run@100000 parallel=true",
        "two:run@100000 parallel=true",
        "three:run@100000 parallel=true"
    )

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task one",
        "FINE: Q10001 scheduled after 100 µs: task two",
        "FINE: Q10002 scheduled after 100 µs: task three",
        "FINE: Q10000 starting              : task one",
        "FINE: Q10000 finished run in   0 µs: task one",
        "FINE: Q10001 starting              : task two",
        "FINE: Q10001 finished run in   0 µs: task two",
        "FINE: Q10002 starting              : task three",
        "FINE: Q10002 finished run in   0 µs: task three"
    )
  }

  /** Test the introspection method [TaskQueue.scheduledTasks]. */
  @Test fun scheduledTasks() {
    redQueue.execute("task one", 100.µs) {
      // Do nothing.
    }

    redQueue.execute("task two", 200.µs) {
      // Do nothing.
    }

    assertThat(redQueue.scheduledTasks.toString()).isEqualTo("[task one, task two]")

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task one",
        "FINE: Q10000 scheduled after 200 µs: task two"
    )
  }

  /**
   * We don't track the active task in scheduled tasks. This behavior might be a mistake, but it's
   * cumbersome to implement properly because the active task might be a cancel.
   */
  @Test fun scheduledTasksDoesNotIncludeRunningTask() {
    val task = object : Task("task one") {
      val schedules = mutableListOf(200.µs)
      override fun runOnce(): Long {
        if (schedules.isNotEmpty()) {
          redQueue.schedule(this, schedules.removeAt(0)) // Add it at the end also.
        }
        log += "scheduledTasks=${redQueue.scheduledTasks}"
        return -1L
      }
    }
    redQueue.schedule(task, 100.µs)

    redQueue.execute("task two", 200.µs) {
      log += "scheduledTasks=${redQueue.scheduledTasks}"
    }

    taskFaker.advanceUntil(100.µs)
    assertThat(log).containsExactly(
        "scheduledTasks=[task two, task one]"
    )

    taskFaker.advanceUntil(200.µs)
    assertThat(log).containsExactly(
        "scheduledTasks=[task two, task one]",
        "scheduledTasks=[task one]"
    )

    taskFaker.advanceUntil(300.µs)
    assertThat(log).containsExactly(
        "scheduledTasks=[task two, task one]",
        "scheduledTasks=[task one]",
        "scheduledTasks=[]"
    )

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task one",
        "FINE: Q10000 scheduled after 200 µs: task two",
        "FINE: Q10000 starting              : task one",
        "FINE: Q10000 scheduled after 200 µs: task one",
        "FINE: Q10000 finished run in   0 µs: task one",
        "FINE: Q10000 starting              : task two",
        "FINE: Q10000 finished run in   0 µs: task two",
        "FINE: Q10000 starting              : task one",
        "FINE: Q10000 finished run in   0 µs: task one"
    )
  }

  /**
   * The runner doesn't hold references to its queues! Otherwise we'd need a mechanism to clean them
   * up when they're no longer needed and that's annoying. Instead the task runner only tracks which
   * queues have work scheduled.
   */
  @Test fun activeQueuesContainsOnlyQueuesWithScheduledTasks() {
    redQueue.execute("task one", 100.µs) {
      // Do nothing.
    }

    blueQueue.execute("task two", 200.µs) {
      // Do nothing.
    }

    taskFaker.advanceUntil(0.µs)
    assertThat(taskRunner.activeQueues()).containsExactly(redQueue, blueQueue)

    taskFaker.advanceUntil(100.µs)
    assertThat(taskRunner.activeQueues()).containsExactly(blueQueue)

    taskFaker.advanceUntil(200.µs)
    assertThat(taskRunner.activeQueues()).isEmpty()

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task one",
        "FINE: Q10001 scheduled after 200 µs: task two",
        "FINE: Q10000 starting              : task one",
        "FINE: Q10000 finished run in   0 µs: task one",
        "FINE: Q10001 starting              : task two",
        "FINE: Q10001 finished run in   0 µs: task two"
    )
  }

  @Test fun taskNameIsUsedForThreadNameWhenRunning() {
    redQueue.execute("lucky task") {
      log += "run threadName:${Thread.currentThread().name}"
    }

    taskFaker.advanceUntil(0.µs)
    assertThat(log).containsExactly("run threadName:lucky task")

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after   0 µs: lucky task",
        "FINE: Q10000 starting              : lucky task",
        "FINE: Q10000 finished run in   0 µs: lucky task"
    )
  }

  @Test fun shutdownSuccessfullyCancelsScheduledTasks() {
    redQueue.execute("task", 100.µs) {
      log += "run@${taskFaker.nanoTime}"
    }

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    redQueue.shutdown()

    taskFaker.advanceUntil(99.µs)
    assertThat(log).isEmpty()

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task",
        "FINE: Q10000 canceled              : task"
    )
  }

  @Test fun shutdownFailsToCancelsScheduledTasks() {
    redQueue.schedule(object : Task("task", false) {
      override fun runOnce(): Long {
        log += "run@${taskFaker.nanoTime}"
        return 50.µs
      }
    }, 100.µs)

    taskFaker.advanceUntil(0.µs)
    assertThat(log).isEmpty()

    redQueue.shutdown()

    taskFaker.advanceUntil(99.µs)
    assertThat(log).isEmpty()

    taskFaker.advanceUntil(100.µs)
    assertThat(log).containsExactly("run@100000")

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 scheduled after 100 µs: task",
        "FINE: Q10000 starting              : task",
        "FINE: Q10000 finished run in   0 µs: task"
    )
  }

  @Test fun scheduleDiscardsTaskWhenShutdown() {
    redQueue.shutdown()

    redQueue.execute("task", 100.µs) {
      // Do nothing.
    }

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 schedule canceled (queue is shutdown): task"
    )
  }

  @Test fun scheduleThrowsWhenShutdown() {
    redQueue.shutdown()

    try {
      redQueue.schedule(object : Task("task", cancelable = false) {
        override fun runOnce(): Long {
          return -1L
        }
      }, 100.µs)
      fail("")
    } catch (_: RejectedExecutionException) {
    }

    taskFaker.assertNoMoreTasks()

    assertThat(testLogHandler.takeAll()).containsExactly(
        "FINE: Q10000 schedule failed (queue is shutdown): task"
    )
  }

  @Test fun idleLatch() {
    redQueue.execute("task") {
      log += "run@${taskFaker.nanoTime}"
    }

    val idleLatch = redQueue.idleLatch()
    assertThat(idleLatch.count).isEqualTo(1)

    taskFaker.advanceUntil(0.µs)
    assertThat(log).containsExactly("run@0")

    assertThat(idleLatch.count).isEqualTo(0)
  }

  @Test fun multipleCallsToIdleLatchReturnSameInstance() {
    redQueue.execute("task") {
      log += "run@${taskFaker.nanoTime}"
    }

    val idleLatch1 = redQueue.idleLatch()
    val idleLatch2 = redQueue.idleLatch()
    assertThat(idleLatch2).isSameAs(idleLatch1)
  }

  private val Int.µs: Long
    get() = this * 1_000L
}
