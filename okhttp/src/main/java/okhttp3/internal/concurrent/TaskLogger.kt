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

import java.util.logging.Level

internal inline fun taskLog(
  task: Task,
  queue: TaskQueue,
  messageBlock: () -> String
) {
  if (TaskRunner.logger.isLoggable(Level.FINE)) {
    log(task, queue, messageBlock())
  }
}

internal inline fun <T> logElapsed(
  task: Task,
  queue: TaskQueue,
  block: () -> T
): T {
  var startNs = -1L
  val loggingEnabled = TaskRunner.logger.isLoggable(Level.FINE)
  if (loggingEnabled) {
    startNs = queue.taskRunner.backend.nanoTime()
    log(task, queue, "starting")
  }

  var completedNormally = false
  try {
    val result = block()
    completedNormally = true
    return result
  } finally {
    if (loggingEnabled) {
      val elapsedNs = queue.taskRunner.backend.nanoTime() - startNs
      if (completedNormally) {
        log(task, queue, "finished in ${formatDuration(elapsedNs)}")
      } else {
        log(task, queue, "failed in ${formatDuration(elapsedNs)}")
      }
    }
  }
}

private fun log(task: Task, queue: TaskQueue, message: String) {
  TaskRunner.logger.fine("${queue.name} $message: ${task.name}")
}

/**
 * Returns a duration in the nearest whole-number units like "999 µs" or "1 ms". This rounds 0.5
 * units away from 0 and 0.499 towards 0. The smallest unit this returns is "µs"; the largest unit
 * it returns is "s". For values in [-499..499] this returns "0 µs".
 */
fun formatDuration(ns: Long): String {
  return when {
    ns <= -999_500_000 -> "${(ns - 500_000_000) / 1_000_000_000} s"
    ns <= -999_500 -> "${(ns - 500_000) / 1_000_000} ms"
    ns <= 0 -> "${(ns - 500) / 1_000} µs"
    ns < 999_500 -> "${(ns + 500) / 1_000} µs"
    ns < 999_500_000 -> "${(ns + 500_000) / 1_000_000} ms"
    else -> "${(ns + 500_000_000) / 1_000_000_000} s"
  }
}
