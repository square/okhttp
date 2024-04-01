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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExtensionMultipleInstancesTest {
  var defaultInstancePort: Int = -1
  var instanceAPort: Int = -1
  var instanceBPort: Int = -1

  @BeforeEach
  fun setup(
    defaultInstance: MockWebServer,
    @MockWebServerInstance("A") instanceA: MockWebServer,
    @MockWebServerInstance("B") instanceB: MockWebServer,
  ) {
    defaultInstancePort = defaultInstance.port
    instanceAPort = instanceA.port
    instanceBPort = instanceB.port
    assertThat(defaultInstance.started).isTrue()
    assertThat(instanceA.started).isTrue()
    assertThat(instanceB.started).isTrue()
    assertThat(defaultInstancePort).isNotEqualTo(instanceAPort)
    assertThat(defaultInstancePort).isNotEqualTo(instanceBPort)
  }

  @AfterEach
  fun tearDown(
    defaultInstance: MockWebServer,
    @MockWebServerInstance("A") instanceA: MockWebServer,
    @MockWebServerInstance("B") instanceB: MockWebServer,
  ) {
    assertThat(defaultInstance.port).isEqualTo(defaultInstancePort)
    assertThat(instanceA.port).isEqualTo(instanceAPort)
    assertThat(instanceB.port).isEqualTo(instanceBPort)
  }

  @Test
  fun testClient(
    defaultInstance: MockWebServer,
    @MockWebServerInstance("A") instanceA: MockWebServer,
    @MockWebServerInstance("B") instanceB: MockWebServer,
  ) {
    assertThat(defaultInstance.port).isEqualTo(defaultInstancePort)
    assertThat(instanceA.port).isEqualTo(instanceAPort)
    assertThat(instanceB.port).isEqualTo(instanceBPort)
  }
}
