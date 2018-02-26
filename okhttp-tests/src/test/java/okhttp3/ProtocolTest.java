/*
 * Copyright (C) 2014 Square, Inc.
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

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class ProtocolTest {
  @Test
  public void testGetKnown() throws IOException {
    assertEquals(Protocol.HTTP_1_0, Protocol.get("http/1.0"));
    assertEquals(Protocol.HTTP_1_1, Protocol.get("http/1.1"));
    assertEquals(Protocol.SPDY_3, Protocol.get("spdy/3.1"));
    assertEquals(Protocol.HTTP_2, Protocol.get("h2"));
    assertEquals(Protocol.H2C, Protocol.get("h2c"));
    assertEquals(Protocol.QUIC, Protocol.get("quic"));
  }

  @Test(expected = IOException.class)
  public void testGetUnknown() throws IOException {
    Protocol.get("tcp");
  }

  @Test
  public void testToString() throws IOException {
    assertEquals("http/1.0", Protocol.HTTP_1_0.toString());
    assertEquals("http/1.1", Protocol.HTTP_1_1.toString());
    assertEquals("spdy/3.1", Protocol.SPDY_3.toString());
    assertEquals("h2", Protocol.HTTP_2.toString());
    assertEquals("h2c", Protocol.H2C.toString());
    assertEquals("quic", Protocol.QUIC.toString());
  }
}
