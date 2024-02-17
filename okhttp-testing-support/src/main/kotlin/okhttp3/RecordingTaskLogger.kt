/*
 * Copyright (C) 2024 Block, Inc.
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
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "CANNOT_OVERRIDE_INVISIBLE_MEMBER")

package okhttp3

import java.util.concurrent.LinkedBlockingQueue
import okhttp3.internal.concurrent.Task
import okhttp3.internal.concurrent.TaskLogger
import okhttp3.internal.concurrent.TaskQueue

class RecordingTaskLogger : TaskLogger {
  private val logs = LinkedBlockingQueue<String>()

  override fun taskLog(
    task: Task,
    queue: TaskQueue,
    messageBlock: () -> String,
  ) {
    logs.add(messageBlock())
  }

  override fun <T> logElapsed(
    task: Task,
    queue: TaskQueue,
    block: () -> T,
  ): T {
    return block()
  }
}
