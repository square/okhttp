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

package okhttp3.mockwebserver;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Random;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RecordedRequestTest {
  Headers headers = new Headers.Builder().build();

  private class FakeSocket extends Socket {
    private final InetAddress localAddress;
    private final int remotePort;
    private final InetAddress remoteAddress;
    private final int localPort;

    private FakeSocket(int localPort) {
      this(Inet4Address.getLoopbackAddress(), localPort);
    }

    private FakeSocket(InetAddress inetAddress, int localPort) {
      this(inetAddress, localPort, inetAddress, 1234);
    }

    private FakeSocket(InetAddress localAddress, int localPort, InetAddress remoteAddress, int remotePort) {
      this.localAddress = localAddress;
      this.localPort = localPort;
      this.remoteAddress = remoteAddress;
      this.remotePort = remotePort;
    }

    @Override public InetAddress getInetAddress() {
      return remoteAddress;
    }

    @Override public InetAddress getLocalAddress() {
      return localAddress;
    }

    @Override public int getLocalPort() {
      return localPort;
    }

    @Override public int getPort() {
      return remotePort;
    }
  }

  @Test public void testIPv4() throws UnknownHostException {
    Socket socket =
        new FakeSocket(InetAddress.getByAddress("127.0.0.1", new byte[] { 127, 0, 0, 1 }), 80);

    RecordedRequest request =
        new RecordedRequest("GET / HTTP/1.1", headers, Collections.<Integer>emptyList(), 0,
            new Buffer(), 0, socket);

    assertEquals("http://127.0.0.1/", request.getRequestUrl().toString());
  }

  @Test public void testIPv6() throws UnknownHostException {
    Socket socket = new FakeSocket(InetAddress.getByAddress("::1",
        new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 }), 80);

    RecordedRequest request =
        new RecordedRequest("GET / HTTP/1.1", headers, Collections.<Integer>emptyList(), 0,
            new Buffer(), 0, socket);

    assertEquals("http://[::1]/", request.getRequestUrl().toString());
  }

  @Test public void testUsesLocal() throws UnknownHostException {
    Socket socket =
        new FakeSocket(InetAddress.getByAddress("127.0.0.1", new byte[] { 127, 0, 0, 1 }), 80);

    RecordedRequest request =
        new RecordedRequest("GET / HTTP/1.1", headers, Collections.<Integer>emptyList(), 0,
            new Buffer(), 0, socket);

    assertEquals("http://127.0.0.1/", request.getRequestUrl().toString());
  }
}
