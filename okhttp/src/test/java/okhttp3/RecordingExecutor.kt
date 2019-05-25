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
package okhttp3

import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

internal class RecordingExecutor(
  private val dispatcherTest: DispatcherTest
) : AbstractExecutorService() {
  private var shutdown: Boolean = false
  private val calls = mutableListOf<RealCall.AsyncCall>()

  override fun execute(command: Runnable) {
    if (shutdown) throw RejectedExecutionException()
    calls.add(command as RealCall.AsyncCall)
  }

  fun assertJobs(vararg expectedUrls: String) {
    val actualUrls = calls.map { it.request().url.toString() }
    assertThat(actualUrls).containsExactly(*expectedUrls)
  }

  fun finishJob(url: String) {
    val i = calls.iterator()
    while (i.hasNext()) {
      val call = i.next()
      if (call.request().url.toString() == url) {
        i.remove()
        dispatcherTest.dispatcher.finished(call)
        return
      }
    }
    throw AssertionError("No such job: $url")
  }

  override fun shutdown() {
    shutdown = true
  }

  override fun shutdownNow(): List<Runnable> {
    throw UnsupportedOperationException()
  }

  override fun isShutdown(): Boolean {
    throw UnsupportedOperationException()
  }

  override fun isTerminated(): Boolean {
    throw UnsupportedOperationException()
  }

  override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
    throw UnsupportedOperationException()
  }
}
