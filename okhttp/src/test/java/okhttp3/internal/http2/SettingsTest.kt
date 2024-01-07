/*
 * Copyright (C) 2012 Square, Inc.
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
package okhttp3.internal.http2

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class SettingsTest {
  @Test
  fun unsetField() {
    val settings = Settings()
    assertThat(settings.isSet(Settings.MAX_CONCURRENT_STREAMS)).isFalse()
    assertThat(settings.getMaxConcurrentStreams()).isEqualTo(Int.MAX_VALUE)
  }

  @Test
  fun setFields() {
    val settings = Settings()
    settings[Settings.HEADER_TABLE_SIZE] = 8096
    assertThat(settings.headerTableSize).isEqualTo(8096)
    assertThat(settings.getEnablePush(true)).isTrue()
    settings[Settings.ENABLE_PUSH] = 1
    assertThat(settings.getEnablePush(false)).isTrue()
    settings.clear()
    assertThat(settings.getMaxConcurrentStreams()).isEqualTo(Int.MAX_VALUE)
    settings[Settings.MAX_CONCURRENT_STREAMS] = 75
    assertThat(settings.getMaxConcurrentStreams()).isEqualTo(75)
    settings.clear()
    assertThat(settings.getMaxFrameSize(16384)).isEqualTo(16384)
    settings[Settings.MAX_FRAME_SIZE] = 16777215
    assertThat(settings.getMaxFrameSize(16384)).isEqualTo(16777215)
    assertThat(settings.getMaxHeaderListSize(-1)).isEqualTo(-1)
    settings[Settings.MAX_HEADER_LIST_SIZE] = 16777215
    assertThat(settings.getMaxHeaderListSize(-1)).isEqualTo(16777215)
    assertThat(settings.initialWindowSize).isEqualTo(
      Settings.DEFAULT_INITIAL_WINDOW_SIZE,
    )
    settings[Settings.INITIAL_WINDOW_SIZE] = 108
    assertThat(settings.initialWindowSize).isEqualTo(108)
  }

  @Test
  fun merge() {
    val a = Settings()
    a[Settings.HEADER_TABLE_SIZE] = 10000
    a[Settings.MAX_HEADER_LIST_SIZE] = 20000
    a[Settings.INITIAL_WINDOW_SIZE] = 30000
    val b = Settings()
    b[Settings.MAX_HEADER_LIST_SIZE] = 40000
    b[Settings.INITIAL_WINDOW_SIZE] = 50000
    b[Settings.MAX_CONCURRENT_STREAMS] = 60000
    a.merge(b)
    assertThat(a.headerTableSize).isEqualTo(10000)
    assertThat(a.getMaxHeaderListSize(-1)).isEqualTo(40000)
    assertThat(a.initialWindowSize).isEqualTo(50000)
    assertThat(a.getMaxConcurrentStreams()).isEqualTo(60000)
  }
}
