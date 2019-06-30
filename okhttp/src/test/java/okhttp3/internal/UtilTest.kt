/*
 * Copyright (C) 2012 The Android Open Source Project
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
package okhttp3.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.fail
import org.junit.Test
import java.util.LinkedHashMap

class UtilTest {
  @Test fun immutableMap() {
    val map = LinkedHashMap<String, String>()
    map["a"] = "A"
    val immutableCopy = map.toImmutableMap()
    assertThat(mapOf("a" to "A")).isEqualTo(immutableCopy)
    map.clear()
    assertThat(mapOf("a" to "A")).isEqualTo(immutableCopy)
    try {
      (immutableCopy as MutableMap).clear()
      fail()
    } catch (_: UnsupportedOperationException) {
    }
  }
}
