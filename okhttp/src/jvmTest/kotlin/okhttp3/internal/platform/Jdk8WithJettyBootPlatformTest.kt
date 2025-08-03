/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.platform

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import okhttp3.internal.platform.Jdk8WithJettyBootPlatform.Companion.buildIfSupported
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class Jdk8WithJettyBootPlatformTest {
  @RegisterExtension
  val platform = PlatformRule()

  @Test
  fun testBuildsWithJettyBoot() {
    assumeTrue(System.getProperty("java.specification.version") == "1.8")
    platform.assumeJettyBootEnabled()
    assertThat(buildIfSupported()).isNotNull()
  }

  @Test
  fun testNotBuildWithOther() {
    assumeFalse(System.getProperty("java.specification.version") == "1.8")
    assertThat(buildIfSupported()).isNull()
  }
}
