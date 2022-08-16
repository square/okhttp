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
package mockwebserver3.junit5.internal

import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockWebServerExtension::class)
class ExtensionMultipleTestsTest() {
  @Test
  fun testClient1(
    defaultInstance: MockWebServer,
    @MockWebServerInstance("A") instanceA: MockWebServer,
  ) {
    assertThat(seenInstances.add(defaultInstance.port)).isTrue()
    assertThat(seenInstances.add(instanceA.port)).isTrue()
  }

  @Test
  fun testClient2(
    defaultInstance: MockWebServer,
    @MockWebServerInstance("A") instanceA: MockWebServer,
  ) {
    assertThat(seenInstances.add(defaultInstance.port)).isTrue()
    assertThat(seenInstances.add(instanceA.port)).isTrue()
  }

  companion object {
    val seenInstances = mutableSetOf<Int>()
  }
}
