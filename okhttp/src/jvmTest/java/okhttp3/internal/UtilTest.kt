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

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.LinkedHashMap
import okio.buffer
import okio.source
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

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

  @Test fun socketIsHealthy() {
    val localhost = InetAddress.getLoopbackAddress()
    val serverSocket = ServerSocket(0, 1, localhost)

    val socket = Socket()
    socket.connect(serverSocket.localSocketAddress)
    val socketSource = socket.source().buffer()

    assertThat(socket.isHealthy(socketSource)).isTrue()

    serverSocket.close()
    assertThat(socket.isHealthy(socketSource)).isFalse()
  }
}
