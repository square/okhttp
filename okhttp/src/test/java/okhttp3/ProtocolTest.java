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
package okhttp3;

import java.io.IOException;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ProtocolTest {
  @Test
  public void testGetKnown() throws IOException {
    assertThat(Protocol.get("http/1.0")).isEqualTo(Protocol.HTTP_1_0);
    assertThat(Protocol.get("http/1.1")).isEqualTo(Protocol.HTTP_1_1);
    assertThat(Protocol.get("spdy/3.1")).isEqualTo(Protocol.SPDY_3);
    assertThat(Protocol.get("h2")).isEqualTo(Protocol.HTTP_2);
    assertThat(Protocol.get("h2_prior_knowledge")).isEqualTo(Protocol.H2_PRIOR_KNOWLEDGE);
    assertThat(Protocol.get("quic")).isEqualTo(Protocol.QUIC);
  }

  @Test(expected = IOException.class)
  public void testGetUnknown() throws IOException {
    Protocol.get("tcp");
  }

  @Test
  public void testToString() throws IOException {
    assertThat(Protocol.HTTP_1_0.toString()).isEqualTo("http/1.0");
    assertThat(Protocol.HTTP_1_1.toString()).isEqualTo("http/1.1");
    assertThat(Protocol.SPDY_3.toString()).isEqualTo("spdy/3.1");
    assertThat(Protocol.HTTP_2.toString()).isEqualTo("h2");
    assertThat(Protocol.H2_PRIOR_KNOWLEDGE.toString()).isEqualTo("h2_prior_knowledge");
    assertThat(Protocol.QUIC.toString()).isEqualTo("quic");
  }
}
