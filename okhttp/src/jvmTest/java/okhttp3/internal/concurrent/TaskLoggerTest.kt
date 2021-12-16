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
package okhttp3.internal.concurrent

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TaskLoggerTest {
  @Test fun formatTime() {
    /* ktlint-disable */
    assertThat(formatDuration(-3_499_999_999L)).isEqualTo(" -3 s ")
    assertThat(formatDuration(-3_000_000_000L)).isEqualTo(" -3 s ")
    assertThat(formatDuration(-2_500_000_000L)).isEqualTo(" -3 s ")
    assertThat(formatDuration(-2_499_999_999L)).isEqualTo(" -2 s ")
    assertThat(formatDuration(-1_500_000_000L)).isEqualTo(" -2 s ")
    assertThat(formatDuration(-1_499_999_999L)).isEqualTo(" -1 s ")
    assertThat(formatDuration(-1_000_000_000L)).isEqualTo(" -1 s ")
    assertThat(formatDuration(  -999_500_000L)).isEqualTo(" -1 s ")
    assertThat(formatDuration(  -999_499_999L)).isEqualTo("-999 ms")
    assertThat(formatDuration(  -998_500_000L)).isEqualTo("-999 ms")
    assertThat(formatDuration(  -998_499_999L)).isEqualTo("-998 ms")
    assertThat(formatDuration(    -1_499_999L)).isEqualTo(" -1 ms")
    assertThat(formatDuration(      -999_500L)).isEqualTo(" -1 ms")
    assertThat(formatDuration(      -999_499L)).isEqualTo("-999 µs")
    assertThat(formatDuration(      -998_500L)).isEqualTo("-999 µs")
    assertThat(formatDuration(        -1_500L)).isEqualTo(" -2 µs")
    assertThat(formatDuration(        -1_499L)).isEqualTo(" -1 µs")
    assertThat(formatDuration(        -1_000L)).isEqualTo(" -1 µs")
    assertThat(formatDuration(          -999L)).isEqualTo(" -1 µs")
    assertThat(formatDuration(          -500L)).isEqualTo(" -1 µs")
    assertThat(formatDuration(          -499L)).isEqualTo("  0 µs")

    assertThat(formatDuration( 3_499_999_999L)).isEqualTo("  3 s ")
    assertThat(formatDuration( 3_000_000_000L)).isEqualTo("  3 s ")
    assertThat(formatDuration( 2_500_000_000L)).isEqualTo("  3 s ")
    assertThat(formatDuration( 2_499_999_999L)).isEqualTo("  2 s ")
    assertThat(formatDuration( 1_500_000_000L)).isEqualTo("  2 s ")
    assertThat(formatDuration( 1_499_999_999L)).isEqualTo("  1 s ")
    assertThat(formatDuration( 1_000_000_000L)).isEqualTo("  1 s ")
    assertThat(formatDuration(   999_500_000L)).isEqualTo("  1 s ")
    assertThat(formatDuration(   999_499_999L)).isEqualTo("999 ms")
    assertThat(formatDuration(   998_500_000L)).isEqualTo("999 ms")
    assertThat(formatDuration(   998_499_999L)).isEqualTo("998 ms")
    assertThat(formatDuration(     1_499_999L)).isEqualTo("  1 ms")
    assertThat(formatDuration(       999_500L)).isEqualTo("  1 ms")
    assertThat(formatDuration(       999_499L)).isEqualTo("999 µs")
    assertThat(formatDuration(       998_500L)).isEqualTo("999 µs")
    assertThat(formatDuration(         1_500L)).isEqualTo("  2 µs")
    assertThat(formatDuration(         1_499L)).isEqualTo("  1 µs")
    assertThat(formatDuration(         1_000L)).isEqualTo("  1 µs")
    assertThat(formatDuration(           999L)).isEqualTo("  1 µs")
    assertThat(formatDuration(           500L)).isEqualTo("  1 µs")
    assertThat(formatDuration(           499L)).isEqualTo("  0 µs")
    /* ktlint-enable */
  }
}
