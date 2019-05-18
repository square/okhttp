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

import okhttp3.PlatformRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test

class Jdk9PlatformTest {
  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @Rule public val platform = PlatformRule("jdk9")

  @Test
  fun buildsWhenJdk9() {
    assertThat(Jdk9Platform.buildIfSupported()).isNotNull
  }

  @Test
  fun findsAlpnMethods() {
    val platform = Jdk9Platform.buildIfSupported()

    assertThat(platform!!.getProtocolMethod.name).isEqualTo("getApplicationProtocol")
    assertThat(platform.setProtocolMethod.name).isEqualTo("setApplicationProtocols")
  }

  @Test
  @Throws(NoSuchMethodException::class)
  fun testToStringIsClassname() {
    val fu = Object::toString
    assertThat(Jdk9Platform(fu, fu).toString()).isEqualTo("Jdk9Platform")
  }
}
