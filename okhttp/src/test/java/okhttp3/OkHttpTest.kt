/*
 * Copyright (C) 2020 Square, Inc.
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
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OkHttpTest {
  @Test
  fun testVersion() {
    assertThat(OkHttp.VERSION).matches("[0-9]+\\.[0-9]+\\.[0-9]+(-.+)?")
  }

  @Test
  fun testReleaseVersionCompatibity() {
    OkHttp.checkVersion(module = "okhttp-mockwebserver", moduleVersion = "4.20.0", okhttpVersion = "4.20.0")
  }

  @Test
  fun testReleaseVersionIncompatibity() {
    try {
      OkHttp.checkVersion(
          module = "okhttp-mockwebserver", moduleVersion = "4.20.0", okhttpVersion = "4.21.0"
      )
      fail()
    } catch (ise: IllegalStateException) {
      assertEquals("com.squareup.okhttp3:okhttp:4.21.0 not compatible with com.squareup.okhttp3:okhttp-mockwebserver:4.20.0", ise.message)
    }
  }

  @Test
  fun testSnapshotVersionCompatibity() {
    OkHttp.checkVersion(module = "okhttp-mockwebserver", moduleVersion = "4.20.0-SNAPSHOT", okhttpVersion = "4.20.0-SNAPSHOT")
  }

  @Test
  fun testSnapshotReleaseVersionIncompatibity() {
    OkHttp.checkVersion(module = "okhttp-mockwebserver", moduleVersion = "4.20.0", okhttpVersion = "4.21.0-SNAPSHOT")
  }

  @Test
  fun testSnapshotDevVersionIncompatibity() {
    OkHttp.checkVersion(module = "okhttp-mockwebserver", moduleVersion = "dev", okhttpVersion = "4.21.0-SNAPSHOT")
  }
}
