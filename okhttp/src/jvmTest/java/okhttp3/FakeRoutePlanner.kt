/*
 * Copyright (C) 2022 Square, Inc.
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

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingDeque
import okhttp3.internal.concurrent.TaskFaker
import okhttp3.internal.connection.RoutePlanner

class FakeRoutePlanner(
  private val taskFaker: TaskFaker,
) : RoutePlanner, Closeable {
  /**
   * Note that we don't use the same [TaskFaker] for this factory. That way off-topic tasks like
   * connection pool maintenance tasks don't add noise to route planning tests.
   */
  private val factory = TestValueFactory()

  private val pool = factory.newConnectionPool()

  val events = LinkedBlockingDeque<String>()
  var canceled = false
  var hasFailure = false
  private var nextPlanId = 0
  private var nextPlanIndex = 0
  private val plans = mutableListOf<FakePlan>()

  override val address = factory.newAddress("example.com")

  fun addPlan(): FakePlan {
    return FakePlan(nextPlanId++).also {
      plans += it
    }
  }

  override fun isCanceled() = canceled

  override fun plan(): FakePlan {
    require(nextPlanIndex < plans.size) {
      "not enough plans! call addPlan() in the test to set this up"
    }
    val result = plans[nextPlanIndex++]
    events += "take plan ${result.id}"
    return result
  }

  override fun trackFailure(e: IOException) {
    events += "tracking failure: $e"
    hasFailure = true
  }

  override fun hasFailure() = hasFailure

  override fun hasMoreRoutes(): Boolean {
    return nextPlanIndex < plans.size
  }

  override fun sameHostAndPort(url: HttpUrl): Boolean {
    return url.host == address.url.host && url.port == address.url.port
  }

  override fun close() {
    factory.close()
  }

  inner class FakePlan(
    val id: Int
  ) : RoutePlanner.Plan {
    var canceled = false
    val connection = factory.newConnection(pool, factory.newRoute(address))

    override var isConnected = false
    var connectDelayNanos = 0L
    var connectThrowable: Throwable? = null

    override fun connect() {
      check(!isConnected) { "already connected" }
      events += "plan $id connecting..."

      taskFaker.sleep(connectDelayNanos)

      when {
        connectThrowable != null -> {
          events += "plan $id connect failed"
          throw connectThrowable!!
        }
        canceled -> {
          events += "plan $id connect canceled"
        }
        else -> {
          events += "plan $id connected"
          isConnected = true
        }
      }
    }

    override fun handleSuccess() = connection

    override fun cancel() {
      events += "plan $id cancel"
      canceled = true
    }
  }
}
