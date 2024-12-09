/*
 * Copyright (C) 2018 Square, Inc.
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

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.io.IOException
import okhttp3.Protocol.Companion.get
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProtocolTest {
  @Test
  fun testGetKnown() {
    assertThat(get("http/1.0")).isEqualTo(Protocol.HTTP_1_0)
    assertThat(get("http/1.1")).isEqualTo(Protocol.HTTP_1_1)
    assertThat(get("spdy/3.1")).isEqualTo(Protocol.SPDY_3)
    assertThat(get("h2")).isEqualTo(Protocol.HTTP_2)
    assertThat(get("h2_prior_knowledge")).isEqualTo(Protocol.H2_PRIOR_KNOWLEDGE)
    assertThat(get("quic")).isEqualTo(Protocol.QUIC)
    assertThat(get("h3")).isEqualTo(Protocol.HTTP_3)
    assertThat(get("h3-29")).isEqualTo(Protocol.HTTP_3)
  }

  @Test
  fun testGetUnknown() {
    assertThrows(IOException::class.java) { get("tcp") }
  }

  @Test
  fun testToString() {
    assertThat(Protocol.HTTP_1_0.toString()).isEqualTo("http/1.0")
    assertThat(Protocol.HTTP_1_1.toString()).isEqualTo("http/1.1")
    assertThat(Protocol.SPDY_3.toString()).isEqualTo("spdy/3.1")
    assertThat(Protocol.HTTP_2.toString()).isEqualTo("h2")
    assertThat(Protocol.H2_PRIOR_KNOWLEDGE.toString())
      .isEqualTo("h2_prior_knowledge")
    assertThat(Protocol.QUIC.toString()).isEqualTo("quic")
    assertThat(Protocol.HTTP_3.toString()).isEqualTo("h3")
  }
}
