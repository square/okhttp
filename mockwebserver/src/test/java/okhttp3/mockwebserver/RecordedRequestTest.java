package okhttp3.mockwebserver;

import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Collections;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RecordedRequestTest {
  Headers headers = new Headers.Builder().build();

  private class FakeSocket extends Socket {
    private InetAddress inetAddress;
    private int port;

    private FakeSocket(InetAddress inetAddress, int port) {
      this.inetAddress = inetAddress;
      this.port = port;
    }

    @Override public InetAddress getInetAddress() {
      return inetAddress;
    }

    @Override public int getLocalPort() {
      return port;
    }
  }

  @Test public void testIPv4() throws UnknownHostException {
    Socket socket =
        new FakeSocket(InetAddress.getByAddress("127.0.0.1", new byte[] { 127, 0, 0, 1 }), 80);

    RecordedRequest request =
        new RecordedRequest("GET / HTTP/1.1", headers, Collections.<Integer>emptyList(), 0,
            new Buffer(), 0, socket);

    assertEquals(HttpUrl.get("http://127.0.0.1:80"), request.getRequestUrl());
  }

  @Test public void testIPv6() throws UnknownHostException {
    Socket socket = new FakeSocket(InetAddress.getByAddress("0:0:0:0:0:0:0:1",
        new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 }), 80);

    RecordedRequest request =
        new RecordedRequest("GET / HTTP/1.1", headers, Collections.<Integer>emptyList(), 0,
            new Buffer(), 0, socket);

    assertEquals(HttpUrl.get("http://[0:0:0:0:0:0:0:1]:80"), request.getRequestUrl());
  }
}
