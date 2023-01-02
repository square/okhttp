/*
 * Copyright (C) 2022 Block, Inc.
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
package okhttp3.loom

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionListener
import okhttp3.ConnectionListener.Companion.NONE
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.internal.concurrent.TaskRunner

/**
 * Factory for a [OkHttpClient.Builder] with defaults for Loom execution.
 */
object LoomClientBuilder {
  /**
   * Create a [OkHttpClient.Builder] configured with Loom dispatcher and connection pool.
   */
  @JvmStatic
  fun clientBuilder(connectionListener: ConnectionListener = NONE): OkHttpClient.Builder {
    val backend = LoomBackend()
    val taskRunner = TaskRunner(backend)

    return OkHttpClient.Builder()
      .dispatcher(Dispatcher(backend.executor))
      .connectionPool(
        ConnectionPool(
          maxIdleConnections = 5,
          keepAliveDuration = 5,
          timeUnit = TimeUnit.MINUTES,
          taskRunner = taskRunner,
          connectionListener = connectionListener
        )
      )
      .taskRunner(taskRunner)
  }
}
