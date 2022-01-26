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
package okhttp3.internal.connection

import okhttp3.FakeRoutePlanner
import okhttp3.TestValueFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit test for [FastFallbackExchangeFinder] implementation details. */
internal class FastFallbackExchangeFinderTest {
  private val factory = TestValueFactory()
  private val routePlanner = FakeRoutePlanner(factory)
  private val finder = FastFallbackExchangeFinder(routePlanner, factory.taskRunner)

  @Test
  fun takeConnectedConnection() {
    val plan0 = routePlanner.addPlan()
    plan0.isConnected = true

    val result0 = finder.find()
    assertThat(result0).isEqualTo(plan0.connection)
    assertThat(routePlanner.events.poll()).isEqualTo("take plan 0")
    assertThat(routePlanner.events.poll()).isNull()
  }
}
