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

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import okio.buffer
import okio.source
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UtilTest {
  @Test
  fun socketIsHealthy() {
    val localhost = InetAddress.getLoopbackAddress()
    val serverSocket = ServerSocket(0, 1, localhost)

    val socket = Socket()
    socket.connect(serverSocket.localSocketAddress)
    val socketSource = socket.source().buffer()

    assertThat(socket.isHealthy(socketSource)).isTrue()

    serverSocket.close()
    assertThat(socket.isHealthy(socketSource)).isFalse()
  }

  @Test
  fun testDurationTimeUnit() {
    assertThat(checkDuration("timeout", 0, TimeUnit.MILLISECONDS)).isEqualTo(0)
    assertThat(checkDuration("timeout", 1, TimeUnit.MILLISECONDS)).isEqualTo(1)

    assertThat(
      assertThrows<IllegalStateException> {
        checkDuration("timeout", -1, TimeUnit.MILLISECONDS)
      },
    ).hasMessage("timeout < 0")
    assertThat(
      assertThrows<IllegalArgumentException> {
        checkDuration("timeout", 1, TimeUnit.NANOSECONDS)
      },
    ).hasMessage("timeout too small")
    assertThat(
      assertThrows<IllegalArgumentException> {
        checkDuration(
          "timeout",
          1L + Int.MAX_VALUE.toLong(),
          TimeUnit.MILLISECONDS,
        )
      },
    ).hasMessage("timeout too large")
  }

  @Test
  fun testDurationDuration() {
    assertThat(checkDuration("timeout", 0.milliseconds)).isEqualTo(0)
    assertThat(checkDuration("timeout", 1.milliseconds)).isEqualTo(1)

    assertThat(
      assertThrows<IllegalStateException> {
        checkDuration("timeout", (-1).milliseconds)
      },
    ).hasMessage("timeout < 0")
    assertThat(
      assertThrows<IllegalArgumentException> {
        checkDuration("timeout", 1.nanoseconds)
      },
    ).hasMessage("timeout too small")
    assertThat(
      assertThrows<IllegalArgumentException> {
        checkDuration(
          "timeout",
          (1L + Int.MAX_VALUE).milliseconds,
        )
      },
    ).hasMessage("timeout too large")
  }
}
