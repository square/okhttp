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

/**
 * A set of worker threads that are shared among a set of task queues.
 *
 * The task runner is responsible for managing non-daemon threads. It keeps the process alive while
 * user-visible (ie. non-daemon) tasks are scheduled, and allows the process to exit when only
 * housekeeping (ie. daemon) tasks are scheduled.
 *
 * The task runner is also responsible for releasing held threads when the library is unloaded.
 * This is for the benefit of container environments that implement code unloading.
 *
 * Most applications should share a process-wide [TaskRunner] and use queues for per-client work.
 */
interface TaskRunner {
  fun newQueue(owner: Any): TaskQueue

  /**
   * Returns a snapshot of queues that currently have tasks scheduled. The task runner does not
   * necessarily track queues that have no tasks scheduled.
   */
  fun activeQueues(): List<TaskQueue>
}
